package org.openphc.cce.admin.controller;

import java.util.List;
import java.util.UUID;

import org.openphc.cce.admin.api.ApiResponse;
import org.openphc.cce.admin.api.PageParams;
import org.openphc.cce.admin.dto.CreateUserRequest;
import org.openphc.cce.admin.dto.CreatedUserResponse;
import org.openphc.cce.admin.dto.UpdateUserRequest;
import org.openphc.cce.admin.dto.UserResponse;
import org.openphc.cce.admin.dto.UserStatusRequest;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.service.UserCreateService;
import org.openphc.cce.admin.security.CurrentUser;
import org.openphc.cce.admin.service.UserQueryService;
import org.openphc.cce.admin.service.UserStatusService;
import org.openphc.cce.admin.service.UserUpdateService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** /admin/v1/users: the people who can log in. */
@RestController
public class UserController {

	private final UserCreateService userCreateService;
	private final UserQueryService userQueryService;
	private final UserUpdateService userUpdateService;
	private final UserStatusService userStatusService;

	public UserController(UserCreateService userCreateService, UserQueryService userQueryService,
			UserUpdateService userUpdateService, UserStatusService userStatusService) {
		this.userCreateService = userCreateService;
		this.userQueryService = userQueryService;
		this.userUpdateService = userUpdateService;
		this.userStatusService = userStatusService;
	}

	@PatchMapping("/admin/v1/users/{id}/status")
	public ApiResponse<UserResponse> changeStatus(@PathVariable UUID id, @Valid @RequestBody UserStatusRequest body,
			HttpServletRequest request) {
		UUID callerId = CurrentUser.get(request).id();
		return ApiResponse.of(userStatusService.changeStatus(id, body.status(), callerId));
	}

	@GetMapping("/admin/v1/users")
	public ApiResponse<List<UserResponse>> list(@Valid PageParams params,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) UserStatus status,
			@RequestParam(required = false) UUID roleId,
			@RequestParam(required = false) String facilityScope) {
		Page<UserResponse> page = userQueryService.list(q, status, roleId, facilityScope, params);
		return ApiResponse.page(page.getContent(), params, page.getTotalElements());
	}

	@GetMapping("/admin/v1/users/{id}")
	public ApiResponse<UserResponse> get(@PathVariable UUID id) {
		return ApiResponse.of(userQueryService.get(id));
	}

	@PutMapping("/admin/v1/users/{id}")
	public ApiResponse<UserResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
		return ApiResponse.of(userUpdateService.update(id, request));
	}

	@PostMapping("/admin/v1/users")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<CreatedUserResponse> create(@Valid @RequestBody CreateUserRequest request) {
		return ApiResponse.of(userCreateService.create(request));
	}
}
