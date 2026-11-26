package org.openphc.cce.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of PUT /roles/{id}: the complete editable part of a role. name may be left out; if sent, it must not
 * change. A left-out description means none.
 */
public record UpdateRoleRequest(
		String name,
		@NotBlank @Size(max = 255) String displayName,
		String description) {
}
