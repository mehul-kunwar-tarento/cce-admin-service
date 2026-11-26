package org.openphc.cce.admin.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** "This role sees this menu" (table role_menus). The pair is the key; see RoleMenuId. */
@Entity
@Table(name = "role_menus")
@IdClass(RoleMenuId.class)
public class RoleMenu {

	@Id
	private UUID roleId;
	@Id
	private UUID menuId;

	// Filled by the database default when a row is inserted
	@Column(insertable = false, updatable = false)
	private Instant createdAt;

	/** For JPA only. */
	protected RoleMenu() {
	}

	public UUID getRoleId() {
		return roleId;
	}

	public UUID getMenuId() {
		return menuId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
