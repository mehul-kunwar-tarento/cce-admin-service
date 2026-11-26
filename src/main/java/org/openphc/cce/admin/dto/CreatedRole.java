package org.openphc.cce.admin.dto;

import java.util.List;

/** Audit after of a created role: the role and the permission row added with it. */
public record CreatedRole(RoleSnapshot role, List<PermissionSnapshot> permissions) {
}
