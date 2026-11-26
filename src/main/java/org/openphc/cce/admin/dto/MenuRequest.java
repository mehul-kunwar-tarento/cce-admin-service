package org.openphc.cce.admin.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /menus (and later PUT /menus/{id}). Shape checks only; the parent rules need the database.
 * sortOrder and isActive are objects so that "not sent" stays null instead of becoming 0 or false.
 */
public record MenuRequest(
		@NotNull
		@Size(max = 100)
		@Pattern(regexp = "^[a-z][a-z0-9-]*$",
				message = "must be lowercase letters, digits and hyphens, starting with a letter (e.g. patient-detail)")
		String key,

		@NotBlank @Size(max = 255) String label,
		@NotBlank @Size(max = 512) String path,
		@Size(max = 100) String icon,

		// Text rather than MenuType, so a wrong value gets this message instead of a JSON reading error
		@NotNull
		@Pattern(regexp = "NAV|DETAIL", message = "must be NAV or DETAIL")
		String type,

		UUID parentId,
		@NotNull Integer sortOrder,
		Boolean isActive) {
}
