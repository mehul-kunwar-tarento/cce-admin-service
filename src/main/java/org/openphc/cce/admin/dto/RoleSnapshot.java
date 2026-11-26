package org.openphc.cce.admin.dto;

import java.util.UUID;

import org.openphc.cce.admin.entity.Role;

/** A role's own fields, as stored in audit before/after. */
public record RoleSnapshot(UUID id, String name, String displayName, String description, boolean isSystem) {

	public static RoleSnapshot of(Role role) {
		return new RoleSnapshot(role.getId(), role.getName(), role.getDisplayName(), role.getDescription(),
				role.isSystem());
	}
}
