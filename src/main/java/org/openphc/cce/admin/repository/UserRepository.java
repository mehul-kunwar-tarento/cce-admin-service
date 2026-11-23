package org.openphc.cce.admin.repository;

import java.util.Optional;
import java.util.UUID;

import org.openphc.cce.admin.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

	/** The user whose Keycloak id is the token's sub. */
	Optional<User> findByKeycloakId(String keycloakId);
}
