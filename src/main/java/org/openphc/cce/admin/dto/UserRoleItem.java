package org.openphc.cce.admin.dto;

import java.util.UUID;

import org.openphc.cce.admin.entity.Role;

/** One role of a user, as replies show it. */
public record UserRoleItem(UUID id, String name, String displayName) {

	public static UserRoleItem of(Role role) {
		return new UserRoleItem(role.getId(), role.getName(), role.getDisplayName());
	}
}
