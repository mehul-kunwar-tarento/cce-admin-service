package org.openphc.cce.admin.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A job role (table roles). Its name is also the Keycloak realm role and the permission_name of its rows. */
@Entity
@Table(name = "roles")
public class Role {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private String name;
	private String displayName;
	private String description;
	private boolean isSystem;

	// Filled by the database default when a row is inserted
	@Column(insertable = false, updatable = false)
	private Instant createdAt;
	@Column(insertable = false)
	private Instant updatedAt;

	/** For JPA only. */
	protected Role() {
	}

	/** A new role, not saved yet. Never a system role: those come only from the seed. */
	public Role(String name, String displayName, String description) {
		this.name = name;
		this.displayName = displayName;
		this.description = description;
		this.isSystem = false;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getDescription() {
		return description;
	}

	public boolean isSystem() {
		return isSystem;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
