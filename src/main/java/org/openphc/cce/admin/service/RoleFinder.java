package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.error.ApiException;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.repository.RoleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Finds roles by id for the user endpoints: each id once, and every unknown id named in one 400. */
@Component
public class RoleFinder {

	private final RoleRepository roleRepository;

	public RoleFinder(RoleRepository roleRepository) {
		this.roleRepository = roleRepository;
	}

	/**
	 * The roles of these ids, each once, in a list the caller may sort. Unknown ids → 400 naming every one,
	 * ending with the caller's own last words (e.g. "Nothing was created.").
	 */
	public List<Role> findAll(List<UUID> roleIds, String nothingDone) {
		// A set: the same id twice still means that one role
		Set<UUID> wanted = new LinkedHashSet<>(roleIds);
		List<Role> found = roleRepository.findAllById(wanted);
		if (found.size() == wanted.size()) {
			return new ArrayList<>(found);
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
				"Unknown role id(s): " + String.join(", ", unknown) + ". " + nothingDone);
	}
}
