package org.openphc.cce.admin.controller;

import java.util.List;

import org.openphc.cce.admin.api.ApiResponse;
import org.openphc.cce.admin.dto.MenuResponse;
import org.openphc.cce.admin.service.MenuService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** /admin/v1/menus: the catalog of console screens. */
@RestController
public class MenuController {

	private final MenuService menuService;

	public MenuController(MenuService menuService) {
		this.menuService = menuService;
	}

	@GetMapping("/admin/v1/menus")
	public ApiResponse<List<MenuResponse>> list() {
		return ApiResponse.of(menuService.getTree());
	}
}
