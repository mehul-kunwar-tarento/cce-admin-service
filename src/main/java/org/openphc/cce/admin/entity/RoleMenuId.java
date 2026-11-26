package org.openphc.cce.admin.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** The key of a role_menus row: the pair (role, menu). Neither one alone is unique. */
public class RoleMenuId implements Serializable {

	private UUID roleId;
	private UUID menuId;

	/** For JPA only. */
	protected RoleMenuId() {
	}

	public RoleMenuId(UUID roleId, UUID menuId) {
		this.roleId = roleId;
		this.menuId = menuId;
	}

	// JPA compares keys with these, so two ids with the same pair must count as equal
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof RoleMenuId)) {
			return false;
		}
		RoleMenuId that = (RoleMenuId) other;
		return Objects.equals(roleId, that.roleId) && Objects.equals(menuId, that.menuId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(roleId, menuId);
	}
}
