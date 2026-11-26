package org.openphc.cce.admin.repository;

import java.util.List;
import java.util.UUID;

import org.openphc.cce.admin.entity.RoleMenu;
import org.openphc.cce.admin.entity.RoleMenuId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleMenuRepository extends JpaRepository<RoleMenu, RoleMenuId> {

	/** The links to this menu, ordered by role id so the audit lists them the same way every time. */
	List<RoleMenu> findByMenuIdOrderByRoleId(UUID menuId);
}
