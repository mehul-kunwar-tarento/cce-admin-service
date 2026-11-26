package org.openphc.cce.admin.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** The key of a user_roles row: the pair (user, role). Neither one alone is unique. */
public class UserRoleId implements Serializable {

	private UUID userId;
	private UUID roleId;

	/** For JPA only. */
	protected UserRoleId() {
	}

	public UserRoleId(UUID userId, UUID roleId) {
		this.userId = userId;
		this.roleId = roleId;
	}

	// JPA compares keys with these, so two ids with the same pair must count as equal
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof UserRoleId)) {
			return false;
		}
		UserRoleId that = (UserRoleId) other;
		return Objects.equals(userId, that.userId) && Objects.equals(roleId, that.roleId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(userId, roleId);
	}
}
