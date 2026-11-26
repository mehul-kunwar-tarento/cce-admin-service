package org.openphc.cce.admin.service;

import java.util.Optional;
import java.util.UUID;

import org.openphc.cce.admin.api.PageParams;
import org.openphc.cce.admin.dto.RoleResponse;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.error.ApiException;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.repository.RoleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Roles: list and read, and (later) create, change and delete. */
@Service
public class RoleService {

	private final RoleRepository roleRepository;

	public RoleService(RoleRepository roleRepository) {
		this.roleRepository = roleRepository;
	}

	/** One page of roles, A to Z by name. Only rows of our roles table; other permission sets never appear. */
	@Transactional(readOnly = true)
	public Page<RoleResponse> list(PageParams params) {
		// Pages are counted from 0 in Spring and from 1 in our API
		PageRequest request = PageRequest.of(params.page() - 1, params.pageSize(), Sort.by("name"));
		Page<Role> roles = roleRepository.findAll(request);
		return roles.map(RoleResponse::from);
	}

	@Transactional(readOnly = true)
	public RoleResponse get(UUID id) {
		Optional<Role> found = roleRepository.findById(id);
		if (found.isEmpty()) {
			throw notFound(id);
		}
		return RoleResponse.from(found.get());
	}

	private ApiException notFound(UUID id) {
		return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.ROLE_NOT_FOUND, "No role with id " + id + ".");
	}
}
