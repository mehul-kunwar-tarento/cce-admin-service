package org.openphc.cce.admin.dto;

import java.util.List;

/** Reply of PUT /roles/{id}/menus: the role's menus after the save, and any warnings about them. */
public record RoleMenusResponse(
		List<RoleMenuItem> menus,
		List<String> warnings) {
}
