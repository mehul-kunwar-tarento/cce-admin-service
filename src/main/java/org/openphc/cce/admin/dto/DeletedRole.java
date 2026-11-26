package org.openphc.cce.admin.dto;

import java.util.List;

/** Audit before of a deleted role: the role, the keys of its menus and its permission rows. */
public record DeletedRole(RoleSnapshot role, List<String> menus, List<PermissionSnapshot> permissions) {
}
