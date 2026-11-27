package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.openphc.cce.admin.dto.RoleMenuItem;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.repository.RoleMenuRepository;
import org.openphc.cce.admin.repository.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Which menus a role sees. No Keycloak or gateway call: what a console shows is not an access rule. */
@Service
public class RoleMenuService {

	private final RoleRepository roleRepository;
	private final RoleMenuRepository roleMenuRepository;

	public RoleMenuService(RoleRepository roleRepository, RoleMenuRepository roleMenuRepository) {
		this.roleRepository = roleRepository;
		this.roleMenuRepository = roleMenuRepository;
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
}
