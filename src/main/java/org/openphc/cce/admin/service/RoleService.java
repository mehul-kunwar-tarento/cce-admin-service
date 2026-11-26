package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.openphc.cce.admin.api.PageParams;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditWriter;
import org.openphc.cce.admin.client.GatewaySyncClient;
import org.openphc.cce.admin.client.GatewaySyncException;
import org.openphc.cce.admin.dto.CreateRoleRequest;
import org.openphc.cce.admin.dto.CreatedRole;
import org.openphc.cce.admin.dto.PermissionSnapshot;
import org.openphc.cce.admin.dto.RoleResponse;
import org.openphc.cce.admin.dto.RoleSnapshot;
import org.openphc.cce.admin.entity.ApiPermission;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.error.ApiException;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.repository.ApiPermissionRepository;
import org.openphc.cce.admin.repository.RoleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;

/** Roles: list, read, create, and (later) change and delete. */
@Service
public class RoleService {

	private static final String ENTITY_TYPE = "ROLE";
	// The row every role needs so its users can load their sidebar; it also makes the gateway create the role
	private static final String SIDEBAR_METHOD = "GET";
	private static final String SIDEBAR_PATH = "/admin/v1/me/menus";

	private final RoleRepository roleRepository;
	private final ApiPermissionRepository apiPermissionRepository;
	private final AuditWriter auditWriter;
	private final GatewaySyncClient gatewaySyncClient;
	private final EntityManager entityManager;
	private final TransactionTemplate newTransaction;

	public RoleService(RoleRepository roleRepository, ApiPermissionRepository apiPermissionRepository,
			AuditWriter auditWriter, GatewaySyncClient gatewaySyncClient, EntityManager entityManager,
			PlatformTransactionManager transactionManager) {
		this.roleRepository = roleRepository;
		this.apiPermissionRepository = apiPermissionRepository;
		this.auditWriter = auditWriter;
		this.gatewaySyncClient = gatewaySyncClient;
		this.entityManager = entityManager;
		// A transaction that this class starts and commits itself, so the gateway is called only after the commit
		this.newTransaction = new TransactionTemplate(transactionManager);
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

	/**
	 * Saves the role, its sidebar permission row and the audit row in one transaction, commits, and only then
	 * asks the gateway to sync: the gateway reads the table over its own connection and would not see
	 * uncommitted rows. A failed sync leaves everything saved.
	 */
	public RoleResponse create(CreateRoleRequest request) {
		Role role = newTransaction.execute(status -> insertRole(request));
		// Committed: the gateway can now see the new rows
		syncAfterCreate(role.getId());
		return RoleResponse.from(role);
	}

	private Role insertRole(CreateRoleRequest request) {
		String name = request.name();
		if (roleRepository.existsByName(name)) {
			throw duplicateName("A role named '" + name + "' already exists.");
		}
		if (apiPermissionRepository.existsByPermissionName(name)) {
			throw duplicateName("This name is already used by an existing permission set in the gateway."
					+ " Choose another name.");
		}

		Role role = new Role(name, request.displayName(), emptyToNull(request.description()));
		roleRepository.saveAndFlush(role);
		// Read the row back, so createdAt and updatedAt (set by the database) are in the reply
		entityManager.refresh(role);

		ApiPermission sidebar = new ApiPermission(name, SIDEBAR_METHOD, SIDEBAR_PATH, null, null);
		apiPermissionRepository.saveAndFlush(sidebar);

		List<PermissionSnapshot> permissions = new ArrayList<>();
		permissions.add(PermissionSnapshot.of(sidebar));
		auditWriter.record(AuditAction.ROLE_CREATED, ENTITY_TYPE, role.getId().toString(), null,
				new CreatedRole(RoleSnapshot.of(role), permissions));
		return role;
	}

	private void syncAfterCreate(UUID roleId) {
		try {
			gatewaySyncClient.sync(roleId);
		} catch (GatewaySyncException e) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.KEYCLOAK_SYNC_FAILED,
					"Role created. " + e.getMessage() + " Retry with POST /admin/v1/roles/" + roleId + "/sync.");
		}
	}

	/** A blank optional description means none. */
	private String emptyToNull(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		return text;
	}

	private ApiException notFound(UUID id) {
		return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.ROLE_NOT_FOUND, "No role with id " + id + ".");
	}

	private ApiException duplicateName(String message) {
		return new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_ROLE_NAME, message);
	}
}
