package org.openphc.cce.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserStatus;

/** A user as the API shows it: in the list, alone, and after an edit. Roles A to Z by name. */
public record UserResponse(UUID id, String username, String email, String firstName, String lastName,
		UserStatus status, List<UserRoleItem> roles, String facilityScope, Instant lastLoginAt, Instant createdAt) {

	public static UserResponse of(User user, List<UserRoleItem> roles) {
		return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getFirstName(),
				user.getLastName(), user.getStatus(), roles, user.getFacilityScope(), user.getLastLoginAt(),
				user.getCreatedAt());
	}
}
