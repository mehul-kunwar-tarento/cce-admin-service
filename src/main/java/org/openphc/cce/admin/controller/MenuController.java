package org.openphc.cce.admin.controller;

import java.util.List;
import java.util.UUID;

import org.openphc.cce.admin.api.ApiResponse;
import org.openphc.cce.admin.dto.MenuRequest;
import org.openphc.cce.admin.dto.MenuResponse;
import org.openphc.cce.admin.service.MenuService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

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

	@PostMapping("/admin/v1/menus")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<MenuResponse> create(@Valid @RequestBody MenuRequest request) {
		return ApiResponse.of(menuService.create(request));
	}

	/** Returns the edited menu itself (children empty), not the tree. */
	@PutMapping("/admin/v1/menus/{id}")
	public ApiResponse<MenuResponse> update(@PathVariable UUID id, @Valid @RequestBody MenuRequest request) {
		return ApiResponse.of(menuService.update(id, request));
	}

	@DeleteMapping("/admin/v1/menus/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		menuService.delete(id);
	}
}
