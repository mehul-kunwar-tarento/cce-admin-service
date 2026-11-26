package org.openphc.cce.admin.repository;

import org.openphc.cce.admin.entity.ApiPermission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiPermissionRepository extends JpaRepository<ApiPermission, Long> {

	boolean existsByPermissionName(String permissionName);
}
