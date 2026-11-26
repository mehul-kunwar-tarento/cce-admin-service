package org.openphc.cce.admin.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.openphc.cce.admin.audit.AuditAction;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of change history (table audit_log). Written once, never changed: Hibernate issues no UPDATE
 * for an immutable entity.
 */
@Entity
@Table(name = "audit_log")
@Immutable
public class AuditLog {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID actorId;
	private String actorUsername;

	@Enumerated(EnumType.STRING)
	private AuditAction action;

	private String entityType;
	private String entityId;

	@JdbcTypeCode(SqlTypes.JSON)
	private JsonNode before;
	@JdbcTypeCode(SqlTypes.JSON)
	private JsonNode after;

	private String ipAddress;

	// Filled by the database default when a row is inserted
	@Column(insertable = false, updatable = false)
	private Instant createdAt;

	/** For JPA only. */
	protected AuditLog() {
	}

	public AuditLog(UUID actorId, String actorUsername, AuditAction action, String entityType, String entityId,
			JsonNode before, JsonNode after, String ipAddress) {
		this.actorId = actorId;
		this.actorUsername = actorUsername;
		this.action = action;
		this.entityType = entityType;
		this.entityId = entityId;
		this.before = before;
		this.after = after;
		this.ipAddress = ipAddress;
	}

	public UUID getId() {
		return id;
	}

	public UUID getActorId() {
		return actorId;
	}

	public String getActorUsername() {
		return actorUsername;
	}

	public AuditAction getAction() {
		return action;
	}

	public String getEntityType() {
		return entityType;
	}

	public String getEntityId() {
		return entityId;
	}

	public JsonNode getBefore() {
		return before;
	}

	public JsonNode getAfter() {
		return after;
	}

	public String getIpAddress() {
		return ipAddress;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
