package org.openphc.cce.admin.service;

import java.util.Optional;
import java.util.UUID;

import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditWriter;
import org.openphc.cce.admin.client.KeycloakClient;
import org.openphc.cce.admin.client.KeycloakException;
import org.openphc.cce.admin.dto.StatusSnapshot;
import org.openphc.cce.admin.dto.UserResponse;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.error.ApiException;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Activates, deactivates or locks a user. INACTIVE and LOCKED both close the Keycloak account; only the label
 * differs, so Keycloak is called only when the account opens or closes. Roles are kept either way.
 */
@Service
public class UserStatusService {

	private static final Logger log = LoggerFactory.getLogger(UserStatusService.class);

	private static final String ENTITY_TYPE = "USER";

	private final UserRepository userRepository;
	private final AuditWriter auditWriter;
	private final KeycloakClient keycloakClient;
	private final UserQueryService userQueryService;
	private final TransactionTemplate newTransaction;

	public UserStatusService(UserRepository userRepository, AuditWriter auditWriter, KeycloakClient keycloakClient,
			UserQueryService userQueryService, PlatformTransactionManager transactionManager) {
		this.userRepository = userRepository;
		this.auditWriter = auditWriter;
		this.keycloakClient = keycloakClient;
		this.userQueryService = userQueryService;
		// Opened and committed by this class, so a commit that fails after Keycloak changed can be told apart
		this.newTransaction = new TransactionTemplate(transactionManager);
	}

	public UserResponse changeStatus(UUID id, UserStatus newStatus, UUID callerId) {
		StatusProgress progress = new StatusProgress();
		try {
			newTransaction.executeWithoutResult(status -> changeInTransaction(id, newStatus, callerId, progress));
		} catch (RuntimeException e) {
			if (!progress.keycloakChanged) {
				// Keycloak was not changed: the rollback left both sides as they were
				throw e;
			}
			// Keycloak's account opened or closed, our row rolled back: record it for an operator, no undo
			auditWriter.recordFailure(AuditAction.USER_STATUS_UPDATE_FAILED, ENTITY_TYPE, id.toString(),
					progress.keycloakId, "save user", e.getMessage(),
					"none — make Keycloak match the user record by hand");
			throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.KEYCLOAK_SYNC_FAILED,
					"Status change only partly applied: Keycloak account " + openOrClosed(newStatus)
							+ ", user record still " + progress.oldStatus
							+ ". Keycloak and the user record now differ; an operator must fix it.");
		}
		// Read after the commit, in the same shape as GET /users/{id}
		return userQueryService.get(id);
	}

	/** What the change did so far, so a failure after Keycloak changed is known to be half done. */
	private static class StatusProgress {
		private boolean keycloakChanged;
		private String keycloakId;
		private UserStatus oldStatus;
	}

	private void changeInTransaction(UUID id, UserStatus newStatus, UUID callerId, StatusProgress progress) {
		// Locked: a second change of the same user waits here and then sees this one's result
		Optional<User> found = userRepository.findLockedById(id);
		if (found.isEmpty()) {
			throw UserQueryService.notFound(id);
		}
		User user = found.get();
		if (id.equals(callerId)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
					"You can't change your own status. Ask another administrator.");
		}
		UserStatus oldStatus = user.getStatus();
		if (oldStatus == newStatus) {
			// Same status as now: nothing to save, nothing to tell Keycloak, nothing to audit
			return;
		}
		progress.oldStatus = oldStatus;

		user.changeStatus(newStatus);
		userRepository.saveAndFlush(user);

		// INACTIVE and LOCKED are both a closed account; only a change to or from ACTIVE opens or closes it
		boolean wasOpen = oldStatus == UserStatus.ACTIVE;
		boolean isOpen = newStatus == UserStatus.ACTIVE;
		if (wasOpen != isOpen) {
			setEnabledInKeycloak(user, isOpen);
			progress.keycloakChanged = true;
			progress.keycloakId = user.getKeycloakId();
		}

		auditWriter.record(actionFor(newStatus), ENTITY_TYPE, id.toString(), new StatusSnapshot(oldStatus),
				new StatusSnapshot(newStatus));
	}

	/** Any failure throws, which rolls back the row written before: nothing changes anywhere. */
	private void setEnabledInKeycloak(User user, boolean enabled) {
		try {
			keycloakClient.setEnabled(user.getKeycloakId(), enabled);
		} catch (KeycloakException e) {
			log.warn("Status of user {} not changed: {}", user.getUsername(), e.getMessage());
			throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.KEYCLOAK_SYNC_FAILED,
					"Status not changed. Keycloak error: " + e.getReason() + ". Nothing was changed.");
		}
	}

	private AuditAction actionFor(UserStatus status) {
		if (status == UserStatus.ACTIVE) {
			return AuditAction.USER_ACTIVATED;
		}
		if (status == UserStatus.LOCKED) {
			return AuditAction.USER_LOCKED;
		}
		return AuditAction.USER_DEACTIVATED;
	}

	private String openOrClosed(UserStatus status) {
		if (status == UserStatus.ACTIVE) {
			return "enabled";
		}
		return "disabled";
	}
}
