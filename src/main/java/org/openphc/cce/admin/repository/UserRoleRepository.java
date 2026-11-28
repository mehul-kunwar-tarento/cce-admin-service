package org.openphc.cce.admin.repository;

import java.util.List;
import java.util.UUID;

import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

	/** Whether any user holds the role, whatever the user's status. */
	boolean existsByRoleId(UUID roleId);

	/** The roles a user holds, as links. */
	List<UserRole> findByUserId(UUID userId);
}
