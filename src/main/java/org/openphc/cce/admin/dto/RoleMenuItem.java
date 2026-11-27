package org.openphc.cce.admin.dto;

import java.util.UUID;

import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.MenuType;

/** One menu in a role's menu list: enough for a console to tick the role's boxes. */
public record RoleMenuItem(
		UUID id,
		String key,
		String label,
		MenuType type,
		UUID parentId) {

	public static RoleMenuItem from(Menu menu) {
		return new RoleMenuItem(menu.getId(), menu.getKey(), menu.getLabel(), menu.getType(), menu.getParentId());
	}
}
