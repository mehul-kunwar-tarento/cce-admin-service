package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditWriter;
import org.openphc.cce.admin.dto.DeletedMenu;
import org.openphc.cce.admin.dto.MenuRequest;
import org.openphc.cce.admin.dto.MenuResponse;
import org.openphc.cce.admin.dto.MenuSnapshot;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.MenuType;
import org.openphc.cce.admin.entity.RoleMenu;
import org.openphc.cce.admin.error.ApiException;
import org.openphc.cce.admin.error.ConstraintViolations;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.repository.MenuRepository;
import org.openphc.cce.admin.repository.RoleMenuRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The menu catalog: read as a tree, create, change and delete. */
@Service
public class MenuService {

	private static final String ENTITY_TYPE = "MENU";
	// The unique constraint on menus.key; the guard against two creates racing past the check in create
	private static final String KEY_CONSTRAINT = "uk_menus_key";

	private final MenuRepository menuRepository;
	private final RoleMenuRepository roleMenuRepository;
	private final AuditWriter auditWriter;

	public MenuService(MenuRepository menuRepository, RoleMenuRepository roleMenuRepository,
			AuditWriter auditWriter) {
		this.menuRepository = menuRepository;
		this.roleMenuRepository = roleMenuRepository;
		this.auditWriter = auditWriter;
	}

	/** Every menu, inactive ones included: NAV menus in order, each with its DETAIL menus as children. */
	@Transactional(readOnly = true)
	public List<MenuResponse> getTree() {
		// Sorted by the database, so every list below comes out in sidebar order
		List<Menu> menus = menuRepository.findAllByOrderBySortOrderAscKeyAsc();

		List<MenuResponse> navNodes = new ArrayList<>();
		Map<UUID, MenuResponse> navById = new HashMap<>();
		for (Menu menu : menus) {
			if (menu.getType() == MenuType.NAV) {
				MenuResponse node = MenuResponse.from(menu);
				navNodes.add(node);
				navById.put(menu.getId(), node);
			}
		}

		for (Menu menu : menus) {
			if (menu.getType() == MenuType.DETAIL) {
				MenuResponse parent = navById.get(menu.getParentId());
				if (parent == null) {
					// The create and edit rules never allow this; show it at the top rather than hide it
					navNodes.add(MenuResponse.from(menu));
				} else {
					parent.children().add(MenuResponse.from(menu));
				}
			}
		}
		return navNodes;
	}

	/** Saves a new menu and its audit row in one transaction. */
	@Transactional
	public MenuResponse create(MenuRequest request) {
		if (menuRepository.existsByKey(request.key())) {
			throw duplicateKey(request.key());
		}
		MenuType type = MenuType.valueOf(request.type());
		checkParent(type, request.parentId());

		Menu menu = new Menu(request.key(), request.label(), request.path(), emptyToNull(request.icon()), type,
				request.parentId(), request.sortOrder(), activeOrDefault(request.isActive()));
		try {
			// Insert now rather than at commit, so a database error surfaces here
			menuRepository.saveAndFlush(menu);
		} catch (DataIntegrityViolationException e) {
			// Another request created the same key between the check above and this insert
			if (ConstraintViolations.isViolationOf(e, KEY_CONSTRAINT)) {
				throw duplicateKey(request.key());
			}
			throw e;
		}

		auditWriter.record(AuditAction.MENU_CREATED, ENTITY_TYPE, menu.getId().toString(), null,
				MenuSnapshot.of(menu));
		return MenuResponse.from(menu);
	}

	/**
	 * Replaces a menu with the complete new version in the request. Every check only reads; nothing is
	 * written until all pass. The same values as now write nothing and no audit row.
	 */
	@Transactional
	public MenuResponse update(UUID id, MenuRequest request) {
		Optional<Menu> found = menuRepository.findById(id);
		if (found.isEmpty()) {
			throw notFound(id);
		}
		Menu menu = found.get();
		if (!menu.getKey().equals(request.key())) {
			throw invalid("Menu key cannot be changed (consoles find their screens by it). To use a different"
					+ " key, create a new menu. Nothing was changed.");
		}
		// Optional on create, but an edit sends the whole menu: a missing value must not quietly show a hidden menu
		if (request.isActive() == null) {
			throw invalid("Invalid request: isActive must not be null.");
		}
		// Before the parent rules: a NAV pointing at itself would pass them as "a NAV parent"
		if (id.equals(request.parentId())) {
			throw invalid("A menu cannot be its own parent.");
		}
		MenuType type = MenuType.valueOf(request.type());
		checkParent(type, request.parentId());
		if (type == MenuType.DETAIL && menuRepository.existsByParentId(id)) {
			throw invalid("'" + menu.getKey() + "' has DETAIL menus under it, so it can't become a DETAIL."
					+ " Move or delete them first. Nothing was changed.");
		}

		MenuSnapshot before = MenuSnapshot.of(menu);
		String icon = emptyToNull(request.icon());
		MenuSnapshot wanted = new MenuSnapshot(id, menu.getKey(), request.label(), request.path(), icon, type,
				request.parentId(), request.sortOrder(), request.isActive());
		if (wanted.equals(before)) {
			// Same values as now: nothing to save and nothing to audit
			return MenuResponse.from(menu);
		}

		menu.change(request.label(), request.path(), icon, type, request.parentId(), request.sortOrder(),
				request.isActive());
		menuRepository.saveAndFlush(menu);
		auditWriter.record(AuditAction.MENU_UPDATED, ENTITY_TYPE, id.toString(), before, MenuSnapshot.of(menu));
		return MenuResponse.from(menu);
	}

	/** Deletes a menu and its role links in one transaction. A NAV with DETAIL menus under it is refused. */
	@Transactional
	public void delete(UUID id) {
		Optional<Menu> found = menuRepository.findById(id);
		if (found.isEmpty()) {
			throw notFound(id);
		}
		Menu menu = found.get();
		// Only a NAV can have children, so this is the "NAV with DETAIL menus" case
		if (menuRepository.existsByParentId(id)) {
			throw invalid("'" + menu.getKey() + "' still has DETAIL menus under it. Delete or move its DETAIL menus"
					+ " first. Nothing was deleted.");
		}

		List<RoleMenu> links = roleMenuRepository.findByMenuIdOrderByRoleId(id);
		List<UUID> roleIds = new ArrayList<>();
		for (RoleMenu link : links) {
			roleIds.add(link.getRoleId());
		}
		DeletedMenu before = new DeletedMenu(MenuSnapshot.of(menu), roleIds);

		// The links first: the database refuses to delete a menu that role_menus still points at
		roleMenuRepository.deleteAll(links);
		menuRepository.delete(menu);
		// Delete now rather than at commit, so a database error surfaces here
		menuRepository.flush();

		auditWriter.record(AuditAction.MENU_DELETED, ENTITY_TYPE, id.toString(), before, null);
	}

	/** NAV has no parent; DETAIL has a parent that exists and is a NAV. */
	private void checkParent(MenuType type, UUID parentId) {
		if (type == MenuType.NAV) {
			if (parentId != null) {
				throw invalid("A NAV menu has no parent; leave parentId empty.");
			}
			return;
		}

		if (parentId == null) {
			throw invalid("A DETAIL menu needs a NAV parent in parentId.");
		}
		Optional<Menu> parent = menuRepository.findById(parentId);
		if (parent.isEmpty()) {
			throw invalid("parentId " + parentId + " is not a menu.");
		}
		if (parent.get().getType() != MenuType.NAV) {
			throw invalid("The parent of a DETAIL menu must be a NAV menu; '" + parent.get().getKey()
					+ "' is a DETAIL.");
		}
	}

	/** A blank optional icon means no icon. */
	private String emptyToNull(String icon) {
		if (icon == null || icon.isBlank()) {
			return null;
		}
		return icon;
	}

	/** isActive left out of a new menu means active. */
	private boolean activeOrDefault(Boolean isActive) {
		if (isActive == null) {
			return true;
		}
		return isActive;
	}

	private ApiException notFound(UUID id) {
		return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.MENU_NOT_FOUND, "No menu with id " + id + ".");
	}

	private ApiException duplicateKey(String key) {
		return new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_MENU_KEY,
				"A menu with key '" + key + "' already exists.");
	}

	private ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message);
	}
}
