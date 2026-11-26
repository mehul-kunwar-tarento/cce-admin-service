package org.openphc.cce.admin.dto;

import java.time.Instant;
import java.util.UUID;

import org.openphc.cce.admin.entity.Role;

/** A role as the API returns it (GET, and the POST / PUT replies). */
public record RoleResponse(UUID id, String name, String displayName, String description, boolean isSystem,
		Instant createdAt, Instant updatedAt) {

	public static RoleResponse from(Role role) {
		return new RoleResponse(role.getId(), role.getName(), role.getDisplayName(), role.getDescription(),
				role.isSystem(), role.getCreatedAt(), role.getUpdatedAt());
	}
}
