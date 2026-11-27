package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditWriter;
import org.openphc.cce.admin.client.KeycloakClient;
import org.openphc.cce.admin.dto.CreateUserRequest;
import org.openphc.cce.admin.dto.CreatedUserResponse;
import org.openphc.cce.admin.dto.UserSnapshot;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.error.ApiException;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates a user: the checks in our database first, so Keycloak is only called for a request that can work. */
@Service
public class UserCreateService {

	private static final String ENTITY_TYPE = "USER";

	private final RoleRepository roleRepository;
	private final UserRepository userRepository;
	private final UserRoleRepository userRoleRepository;
	private final AuditWriter auditWriter;
	private final KeycloakClient keycloakClient;
	private final TransactionTemplate newTransaction;

	public UserCreateService(RoleRepository roleRepository, UserRepository userRepository,
			UserRoleRepository userRoleRepository, AuditWriter auditWriter, KeycloakClient keycloakClient,
			PlatformTransactionManager transactionManager) {
		this.roleRepository = roleRepository;
		this.userRepository = userRepository;
		this.userRoleRepository = userRoleRepository;
		this.auditWriter = auditWriter;
		this.keycloakClient = keycloakClient;
		// A short transaction of its own for the save, opened only after Keycloak has answered
		this.newTransaction = new TransactionTemplate(transactionManager);
	}

	public CreatedUserResponse create(CreateUserRequest request) {
		List<Role> roles = findRoles(request.roleIds());
		if (userRepository.existsByEmailInAnyCase(request.email())) {
			throw new ApiException(HttpStatus.CONFLICT, ErrorCode.EMAIL_IN_USE,
					"Email " + request.email() + " is already used by another user.");
		}
		if (userRepository.existsByUsername(request.username())) {
			throw new ApiException(HttpStatus.CONFLICT, ErrorCode.USERNAME_IN_USE,
					"Username " + request.username() + " is already taken.");
		}

		// One order everywhere: Keycloak, the audit row and the reply list the roles A to Z
		roles.sort(Comparator.comparing(Role::getName));
		List<String> roleNames = names(roles);

		// Keycloak first: its changes are final at once, so they are made before our transaction opens
		String keycloakId = keycloakClient.createUser(request.username(), request.email(), request.firstName(),
				request.lastName());
		keycloakClient.assignRealmRoles(keycloakId, roleNames);

		User user = newTransaction.execute(status -> saveUser(request, keycloakId, roles, roleNames));
		return CreatedUserResponse.of(user, roles);
	}

	/** The user, a link per role and the audit row: all saved, or none. */
	private User saveUser(CreateUserRequest request, String keycloakId, List<Role> roles, List<String> roleNames) {
		User user = new User(keycloakId, request.username(), request.email(), request.firstName(),
				request.lastName(), UserStatus.ACTIVE, request.facilityScope());
		userRepository.saveAndFlush(user);
		for (Role role : roles) {
			userRoleRepository.save(new UserRole(user.getId(), role.getId()));
		}
		// Insert now rather than at commit, so a database error surfaces inside this method
		userRoleRepository.flush();

		auditWriter.record(AuditAction.USER_CREATED, ENTITY_TYPE, user.getId().toString(), null,
				UserSnapshot.of(user, roleNames));
		return user;
	}

	private List<String> names(List<Role> roles) {
		List<String> names = new ArrayList<>();
		for (Role role : roles) {
			names.add(role.getName());
		}
		return names;
	}

	/** The roles of these ids, each once. Unknown ids → 400 naming every one of them. */
	private List<Role> findRoles(List<UUID> roleIds) {
		// A set: the same id twice still means that one role
		Set<UUID> wanted = new LinkedHashSet<>(roleIds);
		List<Role> found = roleRepository.findAllById(wanted);
		if (found.size() == wanted.size()) {
			// A copy the caller may sort
			return new ArrayList<>(found);
		}

		Set<UUID> foundIds = new HashSet<>();
		for (Role role : found) {
			foundIds.add(role.getId());
		}
		// Sorted as text, so the message is the same every time
		List<String> unknown = new ArrayList<>();
		for (UUID id : wanted) {
			if (!foundIds.contains(id)) {
				unknown.add(id.toString());
			}
		}
		Collections.sort(unknown);
		throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
				"Unknown role id(s): " + String.join(", ", unknown) + ". Nothing was created.");
	}
}
