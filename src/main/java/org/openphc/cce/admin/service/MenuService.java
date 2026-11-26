package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditWriter;
import org.openphc.cce.admin.dto.MenuRequest;
import org.openphc.cce.admin.dto.MenuResponse;
import org.openphc.cce.admin.dto.MenuSnapshot;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.MenuType;
import org.openphc.cce.admin.error.ApiException;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.repository.MenuRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The menu catalog: read as a tree, create, and (later) change and delete. */
@Service
public class MenuService {

	private static final String ENTITY_TYPE = "MENU";

	private final MenuRepository menuRepository;
	private final AuditWriter auditWriter;

	public MenuService(MenuRepository menuRepository, AuditWriter auditWriter) {
		this.menuRepository = menuRepository;
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
		MenuType type = MenuType.valueOf(request.type());
		checkParent(type, request.parentId());

		Menu menu = new Menu(request.key(), request.label(), request.path(), emptyToNull(request.icon()), type,
				request.parentId(), request.sortOrder(), activeOrDefault(request.isActive()));
		// Insert now rather than at commit, so a database error surfaces on this line, inside this method
		menuRepository.saveAndFlush(menu);

		auditWriter.record(AuditAction.MENU_CREATED, ENTITY_TYPE, menu.getId().toString(), null,
				MenuSnapshot.of(menu));
		return MenuResponse.from(menu);
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

	private ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message);
	}
}
