package org.openphc.cce.admin.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** Body of PUT /users/{id}/roles: the full new set; at least one role, no null item. */
public record UserRolesRequest(@NotEmpty List<@NotNull UUID> roleIds) {
}
