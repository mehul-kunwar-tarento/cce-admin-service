package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditWriter;
import org.openphc.cce.admin.dto.RoleMenuItem;
import org.openphc.cce.admin.dto.RoleMenusRequest;
import org.openphc.cce.admin.dto.RoleMenusResponse;
import org.openphc.cce.admin.dto.RoleMenusSnapshot;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.MenuType;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.RoleMenu;
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

	private static final String ENTITY_TYPE = "ROLE";

	private final RoleRepository roleRepository;
	private final RoleMenuRepository roleMenuRepository;
	private final MenuRepository menuRepository;
	private final AuditWriter auditWriter;

	public RoleMenuService(RoleRepository roleRepository, RoleMenuRepository roleMenuRepository,
			MenuRepository menuRepository, AuditWriter auditWriter) {
		this.roleRepository = roleRepository;
		this.roleMenuRepository = roleMenuRepository;
		this.menuRepository = menuRepository;
		this.auditWriter = auditWriter;
	}

	@Transactional(readOnly = true)
	public List<RoleMenuItem> get(UUID roleId) {
		if (!roleRepository.existsById(roleId)) {
			throw RoleService.notFound(roleId);
		}
		return menusOf(roleId);
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

		Set<UUID> current = currentMenuIds(roleId);
		// The same set as now: nothing to save and nothing to audit
		if (!wanted.equals(current)) {
			saveDifference(roleId, current, wanted);
		}
		List<RoleMenuItem> menus = menusOf(roleId);
		return new RoleMenusResponse(menus, warningsFor(menus));
	}

	/** The role's menus in sidebar order. */
	private List<RoleMenuItem> menusOf(UUID roleId) {
		List<Menu> menus = roleMenuRepository.findMenusOfRole(roleId);
		List<RoleMenuItem> items = new ArrayList<>();
		for (Menu menu : menus) {
			items.add(RoleMenuItem.from(menu));
		}
		return items;
	}

	private Set<UUID> currentMenuIds(UUID roleId) {
		Set<UUID> ids = new HashSet<>();
		for (RoleMenu link : roleMenuRepository.findByRoleId(roleId)) {
			ids.add(link.getMenuId());
		}
		return ids;
	}

	/** Removes the links that are no longer wanted, adds the new ones, and records one audit row. */
	private void saveDifference(UUID roleId, Set<UUID> current, Set<UUID> wanted) {
		RoleMenusSnapshot before = new RoleMenusSnapshot(roleMenuRepository.findMenuKeysOfRole(roleId));

		List<RoleMenu> removed = new ArrayList<>();
		for (RoleMenu link : roleMenuRepository.findByRoleId(roleId)) {
			if (!wanted.contains(link.getMenuId())) {
				removed.add(link);
			}
		}
		List<RoleMenu> added = new ArrayList<>();
		for (UUID menuId : wanted) {
			if (!current.contains(menuId)) {
				added.add(new RoleMenu(roleId, menuId));
			}
		}
		roleMenuRepository.deleteAll(removed);
		roleMenuRepository.saveAll(added);
		// Write now rather than at commit, so a database error surfaces here
		roleMenuRepository.flush();

		RoleMenusSnapshot after = new RoleMenusSnapshot(roleMenuRepository.findMenuKeysOfRole(roleId));
		auditWriter.record(AuditAction.ROLE_MENUS_UPDATED, ENTITY_TYPE, roleId.toString(), before, after);
	}

	/**
	 * One warning per DETAIL whose NAV parent is not in the list. It is saved anyway: a user who also holds
	 * a role that gives the parent still gets it.
	 */
	private List<String> warningsFor(List<RoleMenuItem> menus) {
		Set<UUID> ids = new HashSet<>();
		for (RoleMenuItem menu : menus) {
			ids.add(menu.id());
		}
		List<String> warnings = new ArrayList<>();
		for (RoleMenuItem menu : menus) {
			if (menu.type() == MenuType.DETAIL && !ids.contains(menu.parentId())) {
				String parentKey = menuRepository.findById(menu.parentId()).get().getKey();
				warnings.add("DETAIL '" + menu.key() + "' given without its parent '" + parentKey
						+ "'; users get it only if another of their roles gives '" + parentKey + "'.");
			}
		}
		return warnings;
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
