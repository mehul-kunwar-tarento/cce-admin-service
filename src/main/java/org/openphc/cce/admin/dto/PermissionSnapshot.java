package org.openphc.cce.admin.dto;

import org.openphc.cce.admin.entity.ApiPermission;

/** One permission row of a role, as stored in audit before/after. */
public record PermissionSnapshot(Long id, String httpMethod, String uriPattern, String description,
		String resourceCategory) {

	public static PermissionSnapshot of(ApiPermission permission) {
		return new PermissionSnapshot(permission.getId(), permission.getHttpMethod(), permission.getUriPattern(),
				permission.getDescription(), permission.getResourceCategory());
	}
}
