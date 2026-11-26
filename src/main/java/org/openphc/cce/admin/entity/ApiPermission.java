package org.openphc.cce.admin.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of the gateway's permission table (api_permissions): this role may call this method and path.
 * The table's shape belongs to gateway-service: a number id from the database, and times without a zone.
 */
@Entity
@Table(name = "api_permissions")
public class ApiPermission {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String permissionName;
	// Text, not an enum: rows that aren't ours may hold values we don't know
	private String httpMethod;
	private String uriPattern;
	private String description;
	private String resourceCategory;

	// Filled by the database default when a row is inserted
	@Column(insertable = false, updatable = false)
	private LocalDateTime createdAt;
	@Column(insertable = false)
	private LocalDateTime updatedAt;

	/** For JPA only. */
	protected ApiPermission() {
	}

	/** A new permission row, not saved yet. */
	public ApiPermission(String permissionName, String httpMethod, String uriPattern, String description,
			String resourceCategory) {
		this.permissionName = permissionName;
		this.httpMethod = httpMethod;
		this.uriPattern = uriPattern;
		this.description = description;
		this.resourceCategory = resourceCategory;
	}

	public Long getId() {
		return id;
	}

	public String getPermissionName() {
		return permissionName;
	}

	public String getHttpMethod() {
		return httpMethod;
	}

	public String getUriPattern() {
		return uriPattern;
	}

	public String getDescription() {
		return description;
	}

	public String getResourceCategory() {
		return resourceCategory;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}
}
