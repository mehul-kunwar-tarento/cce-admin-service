package org.openphc.cce.admin.repository;

import java.util.List;

import org.openphc.cce.admin.entity.ApiPermission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiPermissionRepository extends JpaRepository<ApiPermission, Long> {

	boolean existsByPermissionName(String permissionName);

	/** The rows of one permission set (for a role: the rows named after it), oldest first. */
	List<ApiPermission> findByPermissionNameOrderById(String permissionName);
}
