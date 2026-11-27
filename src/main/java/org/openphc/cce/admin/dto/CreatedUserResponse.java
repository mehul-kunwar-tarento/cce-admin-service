package org.openphc.cce.admin.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserStatus;

/** The 201 body of POST /users. Roles in the order given, which the service keeps A to Z by name. */
public record CreatedUserResponse(UUID id, String username, String email, UserStatus status,
		List<UserRoleItem> roles) {

	public static CreatedUserResponse of(User user, List<Role> roles) {
		List<UserRoleItem> items = new ArrayList<>();
		for (Role role : roles) {
			items.add(UserRoleItem.of(role));
		}
		return new CreatedUserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getStatus(), items);
	}
}
