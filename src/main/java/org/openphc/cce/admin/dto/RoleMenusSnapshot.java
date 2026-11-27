package org.openphc.cce.admin.dto;

import java.util.List;

/** The audit before/after of a role's menus: their keys, A to Z. */
public record RoleMenusSnapshot(
		List<String> menus) {
}
