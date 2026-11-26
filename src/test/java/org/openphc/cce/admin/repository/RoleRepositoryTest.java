package org.openphc.cce.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.entity.ApiPermission;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserRoleId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The roles, api_permissions and user_roles tables from Java, read against the seed.
 * Each test runs in a transaction that is rolled back, so tests never see each other's rows.
 */
// The whole app starts (security included), so the JWT, Keycloak and gateway settings need values
@SpringBootTest(properties = { "JWT_ISSUER=http://test-keycloak/realms/cce", "JWT_AUDIENCE=cce-admin-service",
		"KEYCLOAK_BASE_URL=http://test-keycloak", "KEYCLOAK_CLIENT_SECRET=test-secret",
		"GATEWAY_BASE_URL=http://test-gateway" })
@Testcontainers
@Transactional
class RoleRepositoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private ApiPermissionRepository apiPermissionRepository;

	@Autowired
	private UserRoleRepository userRoleRepository;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void superAdminIsTheSeededSystemRole() {
		List<Role> roles = roleRepository.findAll();

		assertThat(roles).hasSize(1);
		Role superAdmin = roles.get(0);
		assertThat(superAdmin.getName()).isEqualTo("SUPER_ADMIN");
		assertThat(superAdmin.getDisplayName()).isEqualTo("Super Admin");
		assertThat(superAdmin.getDescription()).isEqualTo("Full access to the admin API.");
		assertThat(superAdmin.isSystem()).isTrue();
		assertThat(superAdmin.getCreatedAt()).isNotNull();
	}

	@Test
	void superAdminHasItsFivePermissionRows() {
		List<String> methods = new ArrayList<>();
		for (ApiPermission permission : apiPermissionRepository.findAll()) {
			if (permission.getPermissionName().equals("SUPER_ADMIN")) {
				assertThat(permission.getId()).isPositive();
				assertThat(permission.getUriPattern()).isEqualTo("/admin/v1/**");
				assertThat(permission.getResourceCategory()).isEqualTo("Admin");
				assertThat(permission.getCreatedAt()).isNotNull();
				methods.add(permission.getHttpMethod());
			}
		}
		assertThat(methods).containsExactlyInAnyOrder("GET", "POST", "PUT", "PATCH", "DELETE");
	}

	@Test
	void userRoleIsFoundByItsPair() {
		UUID roleId = jdbc.queryForObject("SELECT id FROM roles WHERE name = 'SUPER_ADMIN'", UUID.class);
		UUID userId = jdbc.queryForObject(
				"INSERT INTO users (keycloak_id, username, email, status) VALUES ('kc-ravi', 'ravi', 'ravi@x.com', 'ACTIVE')"
						+ " RETURNING id", UUID.class);
		jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", userId, roleId);

		Optional<UserRole> found = userRoleRepository.findById(new UserRoleId(userId, roleId));

		assertThat(found).isPresent();
		assertThat(found.get().getUserId()).isEqualTo(userId);
		assertThat(found.get().getRoleId()).isEqualTo(roleId);
		assertThat(found.get().getCreatedAt()).isNotNull();
	}
}
