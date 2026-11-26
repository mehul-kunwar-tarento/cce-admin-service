package org.openphc.cce.admin.dto;

import java.util.UUID;

import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.MenuType;

/** A menu's own fields, as stored in audit before/after. */
public record MenuSnapshot(UUID id, String key, String label, String path, String icon, MenuType type,
		UUID parentId, int sortOrder, boolean isActive) {

	public static MenuSnapshot of(Menu menu) {
		return new MenuSnapshot(menu.getId(), menu.getKey(), menu.getLabel(), menu.getPath(), menu.getIcon(),
				menu.getType(), menu.getParentId(), menu.getSortOrder(), menu.isActive());
	}
}
