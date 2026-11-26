package org.openphc.cce.admin.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.openphc.cce.admin.entity.AuditLog;
import org.springframework.data.repository.Repository;

/**
 * Insert and read only: audit history is never changed or deleted. Built on the bare Repository so no
 * delete method exists to call by mistake (JpaRepository would bring deleteById, deleteAll, …).
 */
public interface AuditLogRepository extends Repository<AuditLog, UUID> {

	AuditLog save(AuditLog row);

	Optional<AuditLog> findById(UUID id);

	List<AuditLog> findAll();

	long count();
}
