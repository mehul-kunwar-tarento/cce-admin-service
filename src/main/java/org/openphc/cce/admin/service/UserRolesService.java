package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditWriter;
import org.openphc.cce.admin.client.KeycloakClient;
import org.openphc.cce.admin.client.KeycloakException;
import org.openphc.cce.admin.dto.RolesSnapshot;
import org.openphc.cce.admin.dto.UserResponse;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.error.ApiException;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Replaces a user's whole set of roles. Only the difference is written, here and in Keycloak: roles added are
 * given first, then roles taken away are removed, so a half-done change leaves too many roles, never too few.
 */
@Service
public class UserRolesService {

	private static final Logger log = LoggerFactory.getLogger(UserRolesService.class);

	private static final String ENTITY_TYPE = "USER";

	private final UserRepository userRepository;
	private final UserRoleRepository userRoleRepository;
	private final RoleRepository roleRepository;
	private final RoleFinder roleFinder;
	private final AuditWriter auditWriter;
	private final KeycloakClient keycloakClient;
	private final UserQueryService userQueryService;
	private final TransactionTemplate newTransaction;

	public UserRolesService(UserRepository userRepository, UserRoleRepository userRoleRepository,
			RoleRepository roleRepository, RoleFinder roleFinder, AuditWriter auditWriter,
			KeycloakClient keycloakClient, UserQueryService userQueryService,
			PlatformTransactionManager transactionManager) {
		this.userRepository = userRepository;
		this.userRoleRepository = userRoleRepository;
		this.roleRepository = roleRepository;
		this.roleFinder = roleFinder;
		this.auditWriter = auditWriter;
		this.keycloakClient = keycloakClient;
		this.userQueryService = userQueryService;
		// Opened and committed by this class, so a failure after Keycloak changed can be told apart
		this.newTransaction = new TransactionTemplate(transactionManager);
	}

	public UserResponse replaceRoles(UUID id, List<UUID> roleIds, UUID callerId) {
		RolesProgress progress = new RolesProgress();
		try {
			newTransaction.executeWithoutResult(status -> replaceInTransaction(id, roleIds, callerId, progress));
		} catch (RuntimeException e) {
			if (!progress.keycloakChanged) {
				// Keycloak was not changed: the rollback left both sides as they were
				throw e;
			}
			// Keycloak's roles changed, our rows rolled back: record it for an operator, no undo
			auditWriter.recordFailure(AuditAction.USER_ROLES_UPDATE_FAILED, ENTITY_TYPE, id.toString(),
					progress.keycloakId, progress.step, e.getMessage(),
					"none — make Keycloak match the user record by hand");
			throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.KEYCLOAK_SYNC_FAILED, "Role change only partly"
					+ " applied in Keycloak. Keycloak and the user record now differ; an operator must fix it.");
		}
		// Read after the commit, in the same shape as GET /users/{id}
		return userQueryService.get(id);
	}

	/** What the change did so far: whether Keycloak changed, and which step was running when it stopped. */
	private static class RolesProgress {
		private boolean keycloakChanged;
		private String keycloakId;
		private String step;
	}

	private void replaceInTransaction(UUID id, List<UUID> roleIds, UUID callerId, RolesProgress progress) {
		// Locked: a second change of the same user waits here and then sees this one's result
		Optional<User> found = userRepository.findLockedById(id);
		if (found.isEmpty()) {
			throw UserQueryService.notFound(id);
		}
		User user = found.get();
		if (id.equals(callerId)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
					"You can't change your own roles. Ask another administrator.");
		}

		List<Role> wanted = roleFinder.findAll(roleIds, "Nothing was changed.");
		List<UserRole> links = userRoleRepository.findByUserId(id);
		List<UUID> currentIds = new ArrayList<>();
		for (UserRole link : links) {
			currentIds.add(link.getRoleId());
		}
		List<Role> current = roleRepository.findAllById(currentIds);

		List<Role> toAdd = notIn(wanted, current);
		List<Role> toRemove = notIn(current, wanted);
		if (toAdd.isEmpty() && toRemove.isEmpty()) {
			// Same set as now: nothing to save, nothing to tell Keycloak, nothing to audit
			return;
		}

		Set<UUID> removedIds = idsOf(toRemove);
		for (UserRole link : links) {
			if (removedIds.contains(link.getRoleId())) {
				userRoleRepository.delete(link);
			}
		}
		for (Role role : toAdd) {
			userRoleRepository.save(new UserRole(id, role.getId()));
		}
		// Write now rather than at commit, so a database error surfaces before Keycloak is asked
		userRoleRepository.flush();

		progress.keycloakId = user.getKeycloakId();
		if (!toAdd.isEmpty()) {
			callKeycloak(user, "assign realm roles", progress,
					() -> keycloakClient.assignRealmRoles(user.getKeycloakId(), sortedNames(toAdd)));
		}
		if (!toRemove.isEmpty()) {
			callKeycloak(user, "remove realm roles", progress,
					() -> keycloakClient.removeRealmRoles(user.getKeycloakId(), sortedNames(toRemove)));
		}
		// From here on only our own write can fail
		progress.step = "save user";

		auditWriter.record(AuditAction.USER_ROLES_UPDATED, ENTITY_TYPE, id.toString(),
				new RolesSnapshot(sortedNames(current)), new RolesSnapshot(sortedNames(wanted)));
	}

	/**
	 * One Keycloak call. If it is the first to change Keycloak, a failure means nothing changed anywhere: 502,
	 * rolled back. If an earlier call already changed Keycloak, the failure is passed on as it is, and the
	 * caller records the half-done change.
	 */
	private void callKeycloak(User user, String step, RolesProgress progress, Runnable call) {
		progress.step = step;
		try {
			call.run();
		} catch (KeycloakException e) {
			if (progress.keycloakChanged) {
				throw e;
			}
			log.warn("Roles of user {} not changed: {}", user.getUsername(), e.getMessage());
			throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.KEYCLOAK_SYNC_FAILED,
					"Roles not changed. Keycloak error: " + e.getReason() + ". Nothing was changed.");
		}
		progress.keycloakChanged = true;
	}

	/** The roles of the first list that are not in the second. */
	private List<Role> notIn(List<Role> roles, List<Role> others) {
		Set<UUID> otherIds = idsOf(others);
		List<Role> result = new ArrayList<>();
		for (Role role : roles) {
			if (!otherIds.contains(role.getId())) {
				result.add(role);
			}
		}
		return result;
	}

	private Set<UUID> idsOf(List<Role> roles) {
		Set<UUID> ids = new HashSet<>();
		for (Role role : roles) {
			ids.add(role.getId());
		}
		return ids;
	}

	private List<String> sortedNames(List<Role> roles) {
		List<String> names = new ArrayList<>();
		for (Role role : roles) {
			names.add(role.getName());
		}
		Collections.sort(names);
		return names;
	}
}
