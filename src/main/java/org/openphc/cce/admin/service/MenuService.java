package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.openphc.cce.admin.dto.MenuResponse;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.MenuType;
import org.openphc.cce.admin.repository.MenuRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The menu catalog: read as a tree, and (later) create, change and delete. */
@Service
public class MenuService {

	private final MenuRepository menuRepository;

	public MenuService(MenuRepository menuRepository) {
		this.menuRepository = menuRepository;
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
}
