package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditWriter;
import org.openphc.cce.admin.client.KeycloakClient;
import org.openphc.cce.admin.client.KeycloakConflictException;
import org.openphc.cce.admin.client.KeycloakException;
import org.openphc.cce.admin.client.KeycloakRejectedException;
import org.openphc.cce.admin.dto.CreateUserRequest;
import org.openphc.cce.admin.dto.CreatedUserResponse;
import org.openphc.cce.admin.dto.UserSnapshot;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.error.ApiException;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a user: the checks in our database first, so Keycloak is only called for a request that can work.
 * If a step after the Keycloak account was made fails, the account is deleted once and the failure recorded.
 */
@Service
public class UserCreateService {

	private static final Logger log = LoggerFactory.getLogger(UserCreateService.class);

	private static final String ENTITY_TYPE = "USER";

	private final RoleFinder roleFinder;
	private final UserRepository userRepository;
	private final UserRoleRepository userRoleRepository;
	private final AuditWriter auditWriter;
	private final KeycloakClient keycloakClient;
	private final TransactionTemplate newTransaction;

	public UserCreateService(RoleFinder roleFinder, UserRepository userRepository,
			UserRoleRepository userRoleRepository, AuditWriter auditWriter, KeycloakClient keycloakClient,
			PlatformTransactionManager transactionManager) {
		this.roleFinder = roleFinder;
		this.userRepository = userRepository;
		this.userRoleRepository = userRoleRepository;
		this.auditWriter = auditWriter;
		this.keycloakClient = keycloakClient;
		// A short transaction of its own for the save, opened only after Keycloak has answered
		this.newTransaction = new TransactionTemplate(transactionManager);
	}

	public CreatedUserResponse create(CreateUserRequest request) {
		List<Role> roles = roleFinder.findAll(request.roleIds(), "Nothing was created.");
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
		String keycloakId = createInKeycloak(request);

		try {
			keycloakClient.assignRealmRoles(keycloakId, roleNames);
		} catch (KeycloakException e) {
			throw undoCreate(request, keycloakId, "assign realm roles", e,
					"User was not created. The Keycloak account was created but assigning its realm roles failed: "
							+ e.getReason() + "; it has been removed again. Nothing was changed.");
		}

		User user;
		try {
			user = newTransaction.execute(status -> saveUser(request, keycloakId, roles, roleNames));
		} catch (RuntimeException e) {
			throw undoCreate(request, keycloakId, "save user", e,
					"User was not created. The Keycloak account was created but saving the user failed; it has been"
							+ " removed again. Nothing was changed. Try again.");
		}
		return CreatedUserResponse.of(user, roles);
	}

	/** Creates the Keycloak account. Nothing exists yet if this fails, so there is nothing to undo or record. */
	private String createInKeycloak(CreateUserRequest request) {
		try {
			return keycloakClient.createUser(request.username(), request.email(), request.firstName(),
					request.lastName());
		} catch (KeycloakConflictException e) {
			throw new ApiException(HttpStatus.CONFLICT, ErrorCode.EMAIL_IN_USE, "Keycloak already has an account with"
					+ " this username or email that this service doesn't know. An operator must remove or link it.");
		} catch (KeycloakRejectedException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
					"Keycloak refused the user: " + e.getReason() + ". Nothing was created.");
		} catch (KeycloakException e) {
			// Not the caller's mistake: the operator should see it even though nothing is half done
			log.warn("User {} not created: {}", request.username(), e.getMessage());
			throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.KEYCLOAK_SYNC_FAILED,
					"User was not created. Keycloak could not create the account: " + e.getReason()
							+ ". Nothing was changed. Try again.");
		}
	}

	/** Tries once to delete the account just made, records the failure, and gives the 502 for the caller. */
	private ApiException undoCreate(CreateUserRequest request, String keycloakId, String failedStep,
			RuntimeException error, String messageIfRemoved) {
		String cleanup;
		String message;
		try {
			keycloakClient.deleteUser(keycloakId);
			cleanup = "keycloak user deleted";
			message = messageIfRemoved;
		} catch (KeycloakException deleteFailed) {
			cleanup = "FAILED — delete keycloak user " + keycloakId + " by hand";
			message = "User was not created. The Keycloak account " + keycloakId + " was created but could not be"
					+ " removed. An operator must delete it in Keycloak before retrying with the same username/email.";
		}
		// Logs the ERROR line and writes the row in its own transaction; never throws
		auditWriter.recordFailure(AuditAction.USER_CREATE_FAILED, ENTITY_TYPE, request.username(), keycloakId,
				failedStep, error.getMessage(), cleanup);
		return new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.KEYCLOAK_SYNC_FAILED, message);
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
}
