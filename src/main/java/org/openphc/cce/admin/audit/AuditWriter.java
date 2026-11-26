package org.openphc.cce.admin.audit;

import java.util.UUID;

import org.openphc.cce.admin.entity.AuditLog;
import org.openphc.cce.admin.repository.AuditLogRepository;
import org.openphc.cce.admin.security.CurrentUser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

/** Writes the audit_log row for a change, as part of that change's transaction. */
@Component
public class AuditWriter {

	/** Actor name when there is no logged-in caller, e.g. at startup. */
	public static final String SYSTEM = "SYSTEM";

	// Width of audit_log.ip_address; the longest IPv6 address in text is 45 characters
	private static final int MAX_IP_LENGTH = 45;

	private final AuditLogRepository repository;
	private final ObjectMapper objectMapper;

	public AuditWriter(AuditLogRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	/**
	 * Adds one row inside the caller's transaction, so a rollback removes it too. Without a transaction it
	 * throws instead of writing a row that could outlive a failed change.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(AuditAction action, String entityType, String entityId, Object before, Object after) {
		HttpServletRequest request = currentRequest();
		CurrentUser actor = currentUser(request);

		UUID actorId = null;
		String actorUsername = SYSTEM;
		if (actor != null) {
			actorId = actor.id();
			actorUsername = actor.username();
		}

		AuditLog row = new AuditLog(actorId, actorUsername, action, entityType, entityId, toJson(before),
				toJson(after), ipAddress(request));
		repository.save(row);
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
