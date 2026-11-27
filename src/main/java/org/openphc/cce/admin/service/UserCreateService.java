package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.openphc.cce.admin.dto.CreateUserRequest;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.error.ApiException;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Creates a user: the checks in our database first, so Keycloak is only called for a request that can work. */
@Service
public class UserCreateService {

	private final RoleRepository roleRepository;
	private final UserRepository userRepository;

	public UserCreateService(RoleRepository roleRepository, UserRepository userRepository) {
		this.roleRepository = roleRepository;
		this.userRepository = userRepository;
	}

	public void create(CreateUserRequest request) {
		List<Role> roles = findRoles(request.roleIds());
		if (userRepository.existsByEmailInAnyCase(request.email())) {
			throw new ApiException(HttpStatus.CONFLICT, ErrorCode.EMAIL_IN_USE,
					"Email " + request.email() + " is already used by another user.");
		}
		if (userRepository.existsByUsername(request.username())) {
			throw new ApiException(HttpStatus.CONFLICT, ErrorCode.USERNAME_IN_USE,
					"Username " + request.username() + " is already taken.");
		}
		// Creating the account in Keycloak and saving the user come next
		throw new IllegalStateException("Creating the user is not built yet");
	}

	/** The roles of these ids, each once. Unknown ids → 400 naming every one of them. */
	private List<Role> findRoles(List<UUID> roleIds) {
		// A set: the same id twice still means that one role
		Set<UUID> wanted = new LinkedHashSet<>(roleIds);
		List<Role> found = roleRepository.findAllById(wanted);
		if (found.size() == wanted.size()) {
			return found;
		}

		Set<UUID> foundIds = new HashSet<>();
		for (Role role : found) {
			foundIds.add(role.getId());
		}
		// Sorted as text, so the message is the same every time
		List<String> unknown = new ArrayList<>();
		for (UUID id : wanted) {
			if (!foundIds.contains(id)) {
				unknown.add(id.toString());
			}
		}
		Collections.sort(unknown);
		throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
				"Unknown role id(s): " + String.join(", ", unknown) + ". Nothing was created.");
	}
}
