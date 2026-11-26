package org.openphc.cce.admin.repository;

import org.openphc.cce.admin.entity.RoleMenu;
import org.openphc.cce.admin.entity.RoleMenuId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleMenuRepository extends JpaRepository<RoleMenu, RoleMenuId> {
}
