package org.openphc.cce.admin.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.MenuType;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/** One menu in GET /menus. A NAV holds its DETAIL menus in children; a DETAIL has none. */
public record MenuResponse(
		UUID id,
		String key,
		String label,
		String path,
		String icon,
		MenuType type,
		UUID parentId,
		int sortOrder,
		boolean isActive,
		// The API docs can't work out a list of this same type by themselves, so it is named here
		@ArraySchema(schema = @Schema(ref = "#/components/schemas/MenuResponse")) List<MenuResponse> children) {

	/** A node for this menu, with an empty children list to be filled by the caller. */
	public static MenuResponse from(Menu menu) {
		return new MenuResponse(menu.getId(), menu.getKey(), menu.getLabel(), menu.getPath(), menu.getIcon(),
				menu.getType(), menu.getParentId(), menu.getSortOrder(), menu.isActive(), new ArrayList<>());
	}
}
