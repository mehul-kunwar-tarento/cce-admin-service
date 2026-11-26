package org.openphc.cce.admin.repository;

import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
}
