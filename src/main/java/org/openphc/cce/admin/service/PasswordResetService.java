package org.openphc.cce.admin.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditWriter;
import org.openphc.cce.admin.client.KeycloakClient;
import org.openphc.cce.admin.client.KeycloakException;
import org.openphc.cce.admin.dto.PasswordResetSnapshot;
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
 * Has Keycloak email a user the link to set a new password. Keycloak sends the email and the user types the
 * password on Keycloak's own page: no password passes through this service.
 */
@Service
public class PasswordResetService {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

	private static final String ENTITY_TYPE = "USER";
	// Keycloak's name for "the user must set a new password"
	private static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

	private final UserRepository userRepository;
	private final AuditWriter auditWriter;
	private final KeycloakClient keycloakClient;
	private final TransactionTemplate newTransaction;

	public PasswordResetService(UserRepository userRepository, AuditWriter auditWriter, KeycloakClient keycloakClient,
			PlatformTransactionManager transactionManager) {
		this.userRepository = userRepository;
		this.auditWriter = auditWriter;
		this.keycloakClient = keycloakClient;
		this.newTransaction = new TransactionTemplate(transactionManager);
	}

	public void reset(UUID id) {
		newTransaction.executeWithoutResult(status -> resetInTransaction(id));
	}

	private void resetInTransaction(UUID id) {
		Optional<User> found = userRepository.findById(id);
		if (found.isEmpty()) {
			throw UserQueryService.notFound(id);
		}
		User user = found.get();
		// A link to a closed account would only confuse: the password could be set but not used
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Activate the user first.");
		}

		try {
			keycloakClient.sendUpdatePasswordEmail(user.getKeycloakId());
		} catch (KeycloakException e) {
			log.warn("Password reset email for user {} not sent: {}", user.getUsername(), e.getMessage());
			throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.KEYCLOAK_SYNC_FAILED,
					"Password reset email not sent. Keycloak error: " + endWithPeriod(e.getReason()));
		}

		auditWriter.record(AuditAction.USER_PASSWORD_RESET, ENTITY_TYPE, id.toString(), null,
				new PasswordResetSnapshot(user.getEmail(), List.of(UPDATE_PASSWORD)));
	}

	/** Keycloak's own texts sometimes end with a period already. */
	private String endWithPeriod(String text) {
		if (text.endsWith(".")) {
			return text;
		}
		return text + ".";
	}
}
