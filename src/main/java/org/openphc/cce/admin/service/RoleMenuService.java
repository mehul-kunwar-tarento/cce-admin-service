package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.openphc.cce.admin.dto.RoleMenuItem;
import org.openphc.cce.admin.dto.RoleMenusRequest;
import org.openphc.cce.admin.dto.RoleMenusResponse;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.error.ApiException;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.repository.MenuRepository;
import org.openphc.cce.admin.repository.RoleMenuRepository;
import org.openphc.cce.admin.repository.RoleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Which menus a role sees. No Keycloak or gateway call: what a console shows is not an access rule. */
@Service
public class RoleMenuService {

	private final RoleRepository roleRepository;
	private final RoleMenuRepository roleMenuRepository;
	private final MenuRepository menuRepository;

	public RoleMenuService(RoleRepository roleRepository, RoleMenuRepository roleMenuRepository,
			MenuRepository menuRepository) {
		this.roleRepository = roleRepository;
		this.roleMenuRepository = roleMenuRepository;
		this.menuRepository = menuRepository;
	}

	@Transactional(readOnly = true)
	public List<RoleMenuItem> get(UUID roleId) {
		if (!roleRepository.existsById(roleId)) {
			throw RoleService.notFound(roleId);
		}
		List<Menu> menus = roleMenuRepository.findMenusOfRole(roleId);
		List<RoleMenuItem> items = new ArrayList<>();
		for (Menu menu : menus) {
			items.add(RoleMenuItem.from(menu));
		}
		return items;
	}

	/** Replaces the role's menus with the given set. Every check runs before anything is written. */
	@Transactional
	public RoleMenusResponse put(UUID roleId, RoleMenusRequest request) {
		Optional<Role> found = roleRepository.findById(roleId);
		if (found.isEmpty()) {
			throw RoleService.notFound(roleId);
		}
		Role role = found.get();
		// Before the menu ids: nothing about a system role may change, so a bad id is not worth naming
		if (role.isSystem()) {
			throw RoleService.systemRole(role.getName());
		}
		// The same id twice counts once
		Set<UUID> wanted = new HashSet<>(request.menuIds());
		checkMenusExist(wanted);

		// Saving the new set comes next; for now the reply is the role's current menus
		return new RoleMenusResponse(get(roleId), new ArrayList<>());
	}

	/** 400 naming every id that is not a menu, sorted so the message is the same every time. */
	private void checkMenusExist(Set<UUID> wanted) {
		Set<UUID> existing = new HashSet<>();
		for (Menu menu : menuRepository.findAllById(wanted)) {
			existing.add(menu.getId());
		}
		List<String> unknown = new ArrayList<>();
		for (UUID id : wanted) {
			if (!existing.contains(id)) {
				unknown.add(id.toString());
			}
		}
		if (!unknown.isEmpty()) {
			Collections.sort(unknown);
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
					"Unknown menu id(s): " + String.join(", ", unknown) + ". Nothing was changed.");
		}
	}
}
