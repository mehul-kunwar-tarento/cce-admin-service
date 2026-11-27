package org.openphc.cce.admin.repository;

import java.util.Optional;
import java.util.UUID;

import org.openphc.cce.admin.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

public interface RoleRepository extends JpaRepository<Role, UUID> {

	boolean existsByName(String name);

	/** The role, locked until this transaction ends: a second save of the same role waits here. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Role> findLockedById(UUID id);
}
