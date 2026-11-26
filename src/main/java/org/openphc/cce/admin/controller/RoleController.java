package org.openphc.cce.admin.controller;

import java.util.List;
import java.util.UUID;

import org.openphc.cce.admin.api.ApiResponse;
import org.openphc.cce.admin.api.PageParams;
import org.openphc.cce.admin.dto.CreateRoleRequest;
import org.openphc.cce.admin.dto.RoleResponse;
import org.openphc.cce.admin.dto.UpdateRoleRequest;
import org.openphc.cce.admin.service.RoleService;
import org.springframework.data.domain.Page;
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

/** /admin/v1/roles: job roles. */
@RestController
public class RoleController {

	private final RoleService roleService;

	public RoleController(RoleService roleService) {
		this.roleService = roleService;
	}

	@GetMapping("/admin/v1/roles")
	public ApiResponse<List<RoleResponse>> list(@Valid PageParams params) {
		Page<RoleResponse> page = roleService.list(params);
		return ApiResponse.page(page.getContent(), params, page.getTotalElements());
	}

	@GetMapping("/admin/v1/roles/{id}")
	public ApiResponse<RoleResponse> get(@PathVariable UUID id) {
		return ApiResponse.of(roleService.get(id));
	}

	@PostMapping("/admin/v1/roles")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<RoleResponse> create(@Valid @RequestBody CreateRoleRequest request) {
		return ApiResponse.of(roleService.create(request));
	}

	@PutMapping("/admin/v1/roles/{id}")
	public ApiResponse<RoleResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest request) {
		return ApiResponse.of(roleService.update(id, request));
	}

	@DeleteMapping("/admin/v1/roles/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		roleService.delete(id);
	}
}
