package org.openphc.cce.admin.dto;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /users. Username and email are trimmed and lowercased, as Keycloak stores them. There is no
 * password field: one sent anyway is ignored, so it never reaches Keycloak.
 */
public record CreateUserRequest(
		@NotBlank @Size(min = 3, max = 255) String username,
		@NotBlank @Email @Size(max = 255) String email,
		@NotBlank @Size(max = 255) String firstName,
		@NotBlank @Size(max = 255) String lastName,
		String facilityScope,
		@NotEmpty List<@NotNull UUID> roleIds) {

	// Runs while the record is built, so the checks above already see the cleaned values
	public CreateUserRequest {
		username = trimAndLowercase(username);
		email = trimAndLowercase(email);
	}

	// Locale.ROOT: the same result whatever language the server is set to (Turkish would turn I into ı).
	// Also used by UpdateUserRequest, so both clean the same way
	static String trimAndLowercase(String text) {
		if (text == null) {
			return null;
		}
		return text.trim().toLowerCase(Locale.ROOT);
	}
}
