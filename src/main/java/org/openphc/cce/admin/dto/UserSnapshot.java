package org.openphc.cce.admin.dto;

import java.util.List;
import java.util.UUID;

import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserStatus;

/** A user as audit rows show it; roles are names, A to Z. Never a password: there is no such field. */
public record UserSnapshot(UUID id, String keycloakId, String username, String email, String firstName,
		String lastName, UserStatus status, String facilityScope, List<String> roles) {

	public static UserSnapshot of(User user, List<String> roleNames) {
		return new UserSnapshot(user.getId(), user.getKeycloakId(), user.getUsername(), user.getEmail(),
				user.getFirstName(), user.getLastName(), user.getStatus(), user.getFacilityScope(), roleNames);
	}
}
