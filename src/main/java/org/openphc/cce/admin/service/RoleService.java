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
import org.openphc.cce.admin.dto.DeletedRole;
import org.openphc.cce.admin.dto.PermissionSnapshot;
import org.openphc.cce.admin.dto.RoleResponse;
import org.openphc.cce.admin.dto.RoleSnapshot;
import org.openphc.cce.admin.dto.UpdateRoleRequest;
import org.openphc.cce.admin.entity.ApiPermission;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.RoleMenu;
import org.openphc.cce.admin.error.ApiException;
import org.openphc.cce.admin.error.ConstraintViolations;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.repository.ApiPermissionRepository;
import org.openphc.cce.admin.repository.RoleMenuRepository;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;

/** Roles: list, read, create, change and delete. */
@Service
public class RoleService {

	private static final String ENTITY_TYPE = "ROLE";
	// The row every role needs so its users can load their sidebar; it also makes the gateway create the role
	private static final String SIDEBAR_METHOD = "GET";
	private static final String SIDEBAR_PATH = "/admin/v1/me/menus";
	// The unique constraint on roles.name; the guard against two creates racing past the check in insertRole
	private static final String NAME_CONSTRAINT = "uk_roles_name";
	// The link from user_roles to roles; the guard against a user getting the role while it is being deleted
	private static final String USER_ROLE_CONSTRAINT = "fk_user_roles_role";

	private final RoleRepository roleRepository;
	private final ApiPermissionRepository apiPermissionRepository;
	private final RoleMenuRepository roleMenuRepository;
	private final UserRoleRepository userRoleRepository;
	private final AuditWriter auditWriter;
	private final GatewaySyncClient gatewaySyncClient;
	private final EntityManager entityManager;
	private final TransactionTemplate newTransaction;

	public RoleService(RoleRepository roleRepository, ApiPermissionRepository apiPermissionRepository,
			RoleMenuRepository roleMenuRepository, UserRoleRepository userRoleRepository, AuditWriter auditWriter,
			GatewaySyncClient gatewaySyncClient, EntityManager entityManager,
			PlatformTransactionManager transactionManager) {
		this.roleRepository = roleRepository;
		this.apiPermissionRepository = apiPermissionRepository;
		this.roleMenuRepository = roleMenuRepository;
		this.userRoleRepository = userRoleRepository;
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
		try {
			roleRepository.saveAndFlush(role);
		} catch (DataIntegrityViolationException e) {
			// Another request created the same name between the check above and this insert
			if (ConstraintViolations.isViolationOf(e, NAME_CONSTRAINT)) {
				throw duplicateName("A role named '" + name + "' already exists.");
			}
			throw e;
		}
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

	/**
	 * Changes a role's display name and description. Display fields live only in this service, so there is no
	 * gateway sync. The same values as now write nothing and no audit row.
	 */
	@Transactional
	public RoleResponse update(UUID id, UpdateRoleRequest request) {
		Optional<Role> found = roleRepository.findById(id);
		if (found.isEmpty()) {
			throw notFound(id);
		}
		Role role = found.get();
		// Before the name check: a rename of a system role is refused as "can't change", not as "rename"
		if (role.isSystem()) {
			throw systemRole(role.getName());
		}
		if (request.name() != null && !request.name().equals(role.getName())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
					"Role name cannot be changed (it is used in Keycloak and the gateway). Only displayName and"
							+ " description can be edited. To use a different name, create a new role. Nothing was changed.");
		}

		RoleSnapshot before = RoleSnapshot.of(role);
		String description = emptyToNull(request.description());
		RoleSnapshot wanted = new RoleSnapshot(id, role.getName(), request.displayName(), description, false);
		if (wanted.equals(before)) {
			// Same values as now: nothing to save and nothing to audit
			return RoleResponse.from(role);
		}

		role.change(request.displayName(), description);
		roleRepository.saveAndFlush(role);
		auditWriter.record(AuditAction.ROLE_UPDATED, ENTITY_TYPE, id.toString(), before, RoleSnapshot.of(role));
		return RoleResponse.from(role);
	}

	/**
	 * Deletes a role that no user holds: its menu links, its permission rows and the role, with the audit row,
	 * in one transaction. After the commit the gateway finds no rows for the name and removes the realm role.
	 * A failed sync leaves the delete in place.
	 */
	public void delete(UUID id) {
		String name = newTransaction.execute(status -> deleteRole(id));
		// Committed: the gateway now finds no rows for the name and removes the realm role
		syncAfterDelete(id, name);
	}

	/** The checks, then the delete and its audit row. Returns the deleted role's name. */
	private String deleteRole(UUID id) {
		Optional<Role> found = roleRepository.findById(id);
		if (found.isEmpty()) {
			throw notFound(id);
		}
		Role role = found.get();
		if (role.isSystem()) {
			throw systemRole(role.getName());
		}
		// Users who are not active count too: they get their roles back when they are activated again
		if (userRoleRepository.existsByRoleId(id)) {
			throw roleInUse(role.getName());
		}

		List<RoleMenu> links = roleMenuRepository.findByRoleId(id);
		List<String> menuKeys = roleMenuRepository.findMenuKeysOfRole(id);
		// Only the rows named after this role; other permission sets in the table are never touched
		List<ApiPermission> rows = apiPermissionRepository.findByPermissionNameOrderById(role.getName());
		List<PermissionSnapshot> permissions = new ArrayList<>();
		for (ApiPermission row : rows) {
			permissions.add(PermissionSnapshot.of(row));
		}
		DeletedRole before = new DeletedRole(RoleSnapshot.of(role), menuKeys, permissions);

		roleMenuRepository.deleteAll(links);
		apiPermissionRepository.deleteAll(rows);
		roleRepository.delete(role);
		try {
			// Delete now rather than at commit, so a database error surfaces here
			roleRepository.flush();
		} catch (DataIntegrityViolationException e) {
			// A user got the role between the check above and this delete
			if (ConstraintViolations.isViolationOf(e, USER_ROLE_CONSTRAINT)) {
				throw roleInUse(role.getName());
			}
			throw e;
		}

		auditWriter.record(AuditAction.ROLE_DELETED, ENTITY_TYPE, id.toString(), before, null);
		return role.getName();
	}

	private void syncAfterDelete(UUID roleId, String name) {
		try {
			gatewaySyncClient.sync(roleId);
		} catch (GatewaySyncException e) {
			// The role is gone, so it can't be synced on its own; the gateway's reason is in the warning log
			throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.KEYCLOAK_SYNC_FAILED,
					"Role deleted. Keycloak still has realm role " + name + " until the next successful sync of any"
							+ " role. No user in this service holds it.");
		}
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

	// Also used by RoleMenuService, so both give the same text
	static ApiException notFound(UUID id) {
		return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.ROLE_NOT_FOUND, "No role with id " + id + ".");
	}

	private ApiException systemRole(String name) {
		return new ApiException(HttpStatus.CONFLICT, ErrorCode.SYSTEM_ROLE_IMMUTABLE,
				"'" + name + "' is a system role and can't be changed or deleted.");
	}

	private ApiException roleInUse(String name) {
		return new ApiException(HttpStatus.CONFLICT, ErrorCode.ROLE_IN_USE, "'" + name + "' is still held by at least"
				+ " one user (active or not). Remove it from those users first. Nothing was deleted.");
	}

	private ApiException duplicateName(String message) {
		return new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_ROLE_NAME, message);
	}
}
