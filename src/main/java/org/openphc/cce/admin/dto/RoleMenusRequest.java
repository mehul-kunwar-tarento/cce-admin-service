package org.openphc.cce.admin.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Body of PUT /roles/{id}/menus: the full new set of menus. An empty list is allowed; a missing one is not. */
public record RoleMenusRequest(
		@NotNull List<@NotNull UUID> menuIds) {
}
