package org.openphc.cce.admin.service;

import java.util.Optional;
import java.util.UUID;

import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditWriter;
import org.openphc.cce.admin.client.KeycloakClient;
import org.openphc.cce.admin.client.KeycloakConflictException;
import org.openphc.cce.admin.client.KeycloakException;
import org.openphc.cce.admin.client.KeycloakRejectedException;
import org.openphc.cce.admin.dto.ProfileSnapshot;
import org.openphc.cce.admin.dto.UpdateUserRequest;
import org.openphc.cce.admin.dto.UserResponse;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.error.ApiException;
import org.openphc.cce.admin.error.ConstraintViolations;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Edits a user's profile. Our row is changed first, then Keycloak, inside one open transaction: a Keycloak
 * failure rolls the row back, so nothing changes anywhere.
 */
@Service
public class UserUpdateService {

	private static final Logger log = LoggerFactory.getLogger(UserUpdateService.class);

	private static final String ENTITY_TYPE = "USER";
	// The unique index on lower(email); the guard against two edits racing past the check to the same email
	private static final String EMAIL_CONSTRAINT = "uk_users_email_lower";

	private final UserRepository userRepository;
	private final AuditWriter auditWriter;
	private final KeycloakClient keycloakClient;
	private final UserQueryService userQueryService;
	private final TransactionTemplate newTransaction;

	public UserUpdateService(UserRepository userRepository, AuditWriter auditWriter, KeycloakClient keycloakClient,
			UserQueryService userQueryService, PlatformTransactionManager transactionManager) {
		this.userRepository = userRepository;
		this.auditWriter = auditWriter;
		this.keycloakClient = keycloakClient;
		this.userQueryService = userQueryService;
		// Opened and committed by this class, so a commit that fails after Keycloak changed can be told apart
		this.newTransaction = new TransactionTemplate(transactionManager);
	}

	public UserResponse update(UUID id, UpdateUserRequest request) {
		newTransaction.executeWithoutResult(status -> updateInTransaction(id, request));
		// Read after the commit, in the same shape as GET /users/{id}
		return userQueryService.get(id);
	}

	private void updateInTransaction(UUID id, UpdateUserRequest request) {
		Optional<User> found = userRepository.findById(id);
		if (found.isEmpty()) {
			throw UserQueryService.notFound(id);
		}
		User user = found.get();
		if (request.username() != null && !request.username().equals(user.getUsername())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
					"Username cannot be changed (it is the user's Keycloak login). Nothing was changed.");
		}

		ProfileSnapshot before = ProfileSnapshot.of(user);
		ProfileSnapshot wanted = new ProfileSnapshot(request.firstName(), request.lastName(), request.email(),
				request.facilityScope());
		if (wanted.equals(before)) {
			// Same values as now: nothing to save, nothing to tell Keycloak, nothing to audit
			return;
		}

		boolean emailChanged = !request.email().equals(user.getEmail());
		if (emailChanged && userRepository.existsByEmailInAnyCaseForAnotherUser(request.email(), id)) {
			throw emailInUse(request.email());
		}
		// Keycloak has the email and names, not the facility scope
		boolean keycloakFieldsChanged = emailChanged || !request.firstName().equals(user.getFirstName())
				|| !request.lastName().equals(user.getLastName());

		user.changeProfile(request.email(), request.firstName(), request.lastName(), request.facilityScope());
		try {
			// Write now, so a taken email fails here, before Keycloak is asked
			userRepository.saveAndFlush(user);
		} catch (DataIntegrityViolationException e) {
			// Another edit gave this email to someone else between the check above and this write
			if (ConstraintViolations.isViolationOf(e, EMAIL_CONSTRAINT)) {
				throw emailInUse(request.email());
			}
			throw e;
		}

		if (keycloakFieldsChanged) {
			updateInKeycloak(user);
		}
		auditWriter.record(AuditAction.USER_UPDATED, ENTITY_TYPE, id.toString(), before, ProfileSnapshot.of(user));
	}

	/** Any failure throws, which rolls back the row written above: nothing changes anywhere. */
	private void updateInKeycloak(User user) {
		try {
			keycloakClient.updateUser(user.getKeycloakId(), user.getEmail(), user.getFirstName(), user.getLastName());
		} catch (KeycloakConflictException e) {
			throw new ApiException(HttpStatus.CONFLICT, ErrorCode.EMAIL_IN_USE, "Keycloak already has an account"
					+ " with this email that this service doesn't know. An operator must remove or link it."
					+ " Nothing was changed.");
		} catch (KeycloakRejectedException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
					"Keycloak refused the change: " + e.getReason() + ". Nothing was changed.");
		} catch (KeycloakException e) {
			// Not the caller's mistake: the operator should see it even though nothing is half done
			log.warn("User {} not updated: {}", user.getUsername(), e.getMessage());
			throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.KEYCLOAK_SYNC_FAILED,
					"Profile not changed. Keycloak error: " + e.getReason() + ". Nothing was changed.");
		}
	}

	private ApiException emailInUse(String email) {
		return new ApiException(HttpStatus.CONFLICT, ErrorCode.EMAIL_IN_USE,
				"Email " + email + " is already used by another user.");
	}
}
