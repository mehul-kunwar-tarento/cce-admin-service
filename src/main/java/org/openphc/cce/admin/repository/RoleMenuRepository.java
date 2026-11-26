package org.openphc.cce.admin.repository;

import java.util.List;
import java.util.UUID;

import org.openphc.cce.admin.entity.RoleMenu;
import org.openphc.cce.admin.entity.RoleMenuId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleMenuRepository extends JpaRepository<RoleMenu, RoleMenuId> {

	/** The links to this menu, ordered by role id so the audit lists them the same way every time. */
	List<RoleMenu> findByMenuIdOrderByRoleId(UUID menuId);

	List<RoleMenu> findByRoleId(UUID roleId);

	/** The keys of the menus this role has, A to Z. */
	@Query("SELECT m.key FROM Menu m, RoleMenu rm WHERE rm.menuId = m.id AND rm.roleId = :roleId ORDER BY m.key")
	List<String> findMenuKeysOfRole(@Param("roleId") UUID roleId);
}
