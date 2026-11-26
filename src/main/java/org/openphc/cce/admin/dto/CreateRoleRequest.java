package org.openphc.cce.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /roles. Unknown fields such as isSystem are ignored: a created role is never a system role. */
public record CreateRoleRequest(
		@NotNull
		@Size(max = 100)
		@Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
				message = "must be UPPER_SNAKE_CASE, starting with a letter (e.g. FACILITY_ADMIN)")
		String name,

		@NotBlank @Size(max = 255) String displayName,
		String description) {
}
