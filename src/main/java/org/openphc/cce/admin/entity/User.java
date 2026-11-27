package org.openphc.cce.admin.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A person who can log in: our copy of a Keycloak user (table users). */
@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private String keycloakId;
	private String username;
	private String email;
	private String firstName;
	private String lastName;

	@Enumerated(EnumType.STRING)
	private UserStatus status;

	private String facilityScope;
	private Instant lastLoginAt;

	// Filled by the database default when a row is inserted
	@Column(insertable = false, updatable = false)
	private Instant createdAt;
	@Column(insertable = false)
	private Instant updatedAt;

	/** For JPA only. */
	protected User() {
	}

	/** A new user without a facility scope, not saved yet. */
	public User(String keycloakId, String username, String email, String firstName, String lastName,
			UserStatus status) {
		this(keycloakId, username, email, firstName, lastName, status, null);
	}

	/** A new user, not saved yet. */
	public User(String keycloakId, String username, String email, String firstName, String lastName,
			UserStatus status, String facilityScope) {
		this.keycloakId = keycloakId;
		this.username = username;
		this.email = email;
		this.firstName = firstName;
		this.lastName = lastName;
		this.status = status;
		this.facilityScope = facilityScope;
	}

	public UUID getId() {
		return id;
	}

	public String getKeycloakId() {
		return keycloakId;
	}

	public String getUsername() {
		return username;
	}

	public String getEmail() {
		return email;
	}

	public String getFirstName() {
		return firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public UserStatus getStatus() {
		return status;
	}

	public String getFacilityScope() {
		return facilityScope;
	}

	public Instant getLastLoginAt() {
		return lastLoginAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
