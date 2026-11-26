package org.openphc.cce.admin.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** "This user holds this role" (table user_roles). The pair is the key; see UserRoleId. */
@Entity
@Table(name = "user_roles")
@IdClass(UserRoleId.class)
public class UserRole {

	@Id
	private UUID userId;
	@Id
	private UUID roleId;

	// Filled by the database default when a row is inserted
	@Column(insertable = false, updatable = false)
	private Instant createdAt;

	/** For JPA only. */
	protected UserRole() {
	}

	public UUID getUserId() {
		return userId;
	}

	public UUID getRoleId() {
		return roleId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
