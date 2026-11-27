package org.openphc.cce.admin.dto;

import org.openphc.cce.admin.entity.User;

/** The editable part of a user: what PUT /users/{id} changes and what its audit row shows. */
public record ProfileSnapshot(String firstName, String lastName, String email, String facilityScope) {

	public static ProfileSnapshot of(User user) {
		return new ProfileSnapshot(user.getFirstName(), user.getLastName(), user.getEmail(), user.getFacilityScope());
	}
}
