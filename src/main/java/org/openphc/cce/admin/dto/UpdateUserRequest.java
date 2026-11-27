package org.openphc.cce.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of PUT /users/{id}: the whole profile. Username and email are cleaned as on create. A username, if
 * sent, must be the current one.
 */
public record UpdateUserRequest(
		@NotBlank @Email @Size(max = 255) String email,
		@NotBlank @Size(max = 255) String firstName,
		@NotBlank @Size(max = 255) String lastName,
		String facilityScope,
		String username) {

	public UpdateUserRequest {
		username = CreateUserRequest.trimAndLowercase(username);
		email = CreateUserRequest.trimAndLowercase(email);
	}
}
