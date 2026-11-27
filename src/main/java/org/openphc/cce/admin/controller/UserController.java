package org.openphc.cce.admin.controller;

import org.openphc.cce.admin.api.ApiResponse;
import org.openphc.cce.admin.dto.CreateUserRequest;
import org.openphc.cce.admin.dto.CreatedUserResponse;
import org.openphc.cce.admin.service.UserCreateService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/** /admin/v1/users: the people who can log in. */
@RestController
public class UserController {

	private final UserCreateService userCreateService;

	public UserController(UserCreateService userCreateService) {
		this.userCreateService = userCreateService;
	}

	@PostMapping("/admin/v1/users")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<CreatedUserResponse> create(@Valid @RequestBody CreateUserRequest request) {
		return ApiResponse.of(userCreateService.create(request));
	}
}
