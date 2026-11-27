package org.openphc.cce.admin.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.openphc.cce.admin.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, UUID> {

	/** The user whose Keycloak id is the token's sub. */
	Optional<User> findByKeycloakId(String keycloakId);

	boolean existsByUsername(String username);

	/** Whether any user has this email, in any case. */
	@Query("SELECT COUNT(u) > 0 FROM User u WHERE lower(u.email) = lower(:email)")
	boolean existsByEmailInAnyCase(@Param("email") String email);

	/** Gives the user a role by its name. Returns how many rows were added (0 = no role with that name). */
	@Modifying
	@Query(value = "INSERT INTO user_roles (user_id, role_id) SELECT :userId, id FROM roles WHERE name = :roleName",
			nativeQuery = true)
	int addRole(@Param("userId") UUID userId, @Param("roleName") String roleName);

	/**
	 * Moves last_login_at forward to the token's login time. Only that column changes (a login is not a
	 * profile change), and only if the new time is later, so an older token never moves it back.
	 * Returns how many rows changed (0 or 1).
	 */
	@Modifying
	@Transactional
	@Query("UPDATE User u SET u.lastLoginAt = :authTime"
			+ " WHERE u.id = :id AND (u.lastLoginAt IS NULL OR u.lastLoginAt < :authTime)")
	int moveLastLoginForward(@Param("id") UUID id, @Param("authTime") Instant authTime);
}
