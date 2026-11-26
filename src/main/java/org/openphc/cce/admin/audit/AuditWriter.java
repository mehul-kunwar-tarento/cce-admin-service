package org.openphc.cce.admin.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.openphc.cce.admin.entity.AuditLog;
import org.openphc.cce.admin.repository.AuditLogRepository;
import org.openphc.cce.admin.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Writes audit_log rows: one per change, inside that change's transaction, and failure records in a
 * transaction of their own.
 */
@Component
public class AuditWriter {

	private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

	/** Actor name when there is no logged-in caller, e.g. at startup. */
	public static final String SYSTEM = "SYSTEM";

	// Width of audit_log.ip_address; the longest IPv6 address in text is 45 characters
	private static final int MAX_IP_LENGTH = 45;

	private final AuditLogRepository repository;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate newTransaction;

	public AuditWriter(AuditLogRepository repository, ObjectMapper objectMapper,
			PlatformTransactionManager transactionManager) {
		this.repository = repository;
		this.objectMapper = objectMapper;
		// Failure records are written in a transaction of their own, so the caller's rollback can't remove them
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * Adds one row inside the caller's transaction, so a rollback removes it too. Without a transaction it
	 * throws instead of writing a row that could outlive a failed change.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(AuditAction action, String entityType, String entityId, Object before, Object after) {
		repository.save(newRow(action, entityType, entityId, before, after));
	}

	/**
	 * Records a change that reached Keycloak but not our database (or the reverse), for an operator to fix.
	 * Always logs an ERROR line first, then writes the row in its own transaction, so it survives the
	 * caller's rollback. Never throws: the caller is already handling a failure, and the log line is the
	 * record if the row can't be written (e.g. the database is down).
	 */
	public void recordFailure(AuditAction action, String entityType, String entityId, String keycloakId,
			String failedStep, String error, String cleanup) {
		log.error("{} {} {}: keycloakId={}, failedStep={}, error={}, cleanup={}", action, entityType, entityId,
				keycloakId, failedStep, error, cleanup);
		// The whole transaction, commit included, is inside the try: a commit error is caught here too
		try {
			Map<String, String> details = new LinkedHashMap<>();
			details.put("keycloakId", keycloakId);
			details.put("failedStep", failedStep);
			details.put("error", error);
			details.put("cleanup", cleanup);
			AuditLog row = newRow(action, entityType, entityId, null, details);
			newTransaction.executeWithoutResult(status -> repository.save(row));
		} catch (RuntimeException e) {
			log.error("Audit row for {} {} {} not written; the line above is the only record", action, entityType,
					entityId, e);
		}
	}

	private AuditLog newRow(AuditAction action, String entityType, String entityId, Object before, Object after) {
		HttpServletRequest request = currentRequest();
		CurrentUser actor = currentUser(request);

		UUID actorId = null;
		String actorUsername = SYSTEM;
		if (actor != null) {
			actorId = actor.id();
			actorUsername = actor.username();
		}

		return new AuditLog(actorId, actorUsername, action, entityType, entityId, toJson(before), toJson(after),
				ipAddress(request));
	}

	/** Null outside a request (at startup). */
	private HttpServletRequest currentRequest() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (attributes instanceof ServletRequestAttributes servletAttributes) {
			return servletAttributes.getRequest();
		}
		return null;
	}

	private CurrentUser currentUser(HttpServletRequest request) {
		if (request == null) {
			return null;
		}
		return CurrentUser.get(request);
	}

	/**
	 * Behind the gateway the remote address is always the gateway's, so the caller's address comes from the
	 * first X-Forwarded-For entry. The header is caller-written: an entry that is blank or too long for the
	 * column falls back to the remote address, so a bad header never fails the change.
	 */
	private String ipAddress(HttpServletRequest request) {
		if (request == null) {
			return null;
		}
		String header = request.getHeader("X-Forwarded-For");
		if (header != null) {
			String first = firstEntry(header);
			if (!first.isEmpty() && first.length() <= MAX_IP_LENGTH) {
				return first;
			}
		}
		return request.getRemoteAddr();
	}

	// indexOf rather than split(","): split turns a header of just "," into an empty array
	private String firstEntry(String header) {
		int comma = header.indexOf(',');
		if (comma < 0) {
			return header.trim();
		}
		return header.substring(0, comma).trim();
	}

	private JsonNode toJson(Object value) {
		if (value == null) {
			return null;
		}
		return objectMapper.valueToTree(value);
	}
}
