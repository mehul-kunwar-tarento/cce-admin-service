package org.openphc.cce.admin.controller;

import java.util.List;
import java.util.UUID;

import org.openphc.cce.admin.api.ApiResponse;
import org.openphc.cce.admin.dto.RoleMenuItem;
import org.openphc.cce.admin.service.RoleMenuService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** /admin/v1/roles/{id}/menus: which menus a role sees. */
@RestController
public class RoleMenuController {

	private final RoleMenuService roleMenuService;

	public RoleMenuController(RoleMenuService roleMenuService) {
		this.roleMenuService = roleMenuService;
	}

	@GetMapping("/admin/v1/roles/{id}/menus")
	public ApiResponse<List<RoleMenuItem>> get(@PathVariable UUID id) {
		return ApiResponse.of(roleMenuService.get(id));
	}
}
