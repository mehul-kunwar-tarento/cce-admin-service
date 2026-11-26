package org.openphc.cce.admin.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.security.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * /admin/v1/roles called the way a client calls it: a signed token, an ACTIVE local user, the real
 * seed. Each test runs in a transaction that is rolled back, so tests never see each other's rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class RoleControllerTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

	static TestTokens tokens = new TestTokens();

	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry registry) {
		registry.add("JWT_ISSUER", () -> TestTokens.ISSUER);
		registry.add("JWT_AUDIENCE", () -> TestTokens.AUDIENCE);
		registry.add("KEYCLOAK_BASE_URL", () -> "http://test-keycloak");
		registry.add("KEYCLOAK_CLIENT_SECRET", () -> "test-secret");
		registry.add("GATEWAY_BASE_URL", () -> "http://test-gateway");
		registry.add("JWT_JWK_SET_URI", tokens::jwkSetUri);
	}

	@AfterAll
	static void stopJwksServer() {
		tokens.stop();
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void insertAdmin() {
		jdbc.update("INSERT INTO users (keycloak_id, username, email, status) VALUES (?, ?, ?, ?)",
				"kc-admin", "admin", "admin@x.com", "ACTIVE");
	}

	// ---- GET /roles ----

	@Test
	void listHasPaginationAndOnlyOurRoles() throws Exception {
		// A permission set of another kind: it has rows in the gateway's table but is not a role
		jdbc.update("INSERT INTO api_permissions (permission_name, http_method, uri_pattern)"
				+ " VALUES ('COLLECTOR_EVENTS_WRITE', 'POST', '/collector/v1/events')");

		getRoles("")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].name").value(contains("SUPER_ADMIN")))
				.andExpect(jsonPath("$.pagination.page").value(1))
				.andExpect(jsonPath("$.pagination.pageSize").value(20))
				.andExpect(jsonPath("$.pagination.total").value(1));
	}

	@Test
	void listIsSortedByName() throws Exception {
		insertRole("ZETA");
		insertRole("ALPHA");
		insertRole("MIDDLE");

		getRoles("")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].name").value(contains("ALPHA", "MIDDLE", "SUPER_ADMIN", "ZETA")))
				.andExpect(jsonPath("$.pagination.total").value(4));
	}

	@Test
	void secondPageHasTheNextRoles() throws Exception {
		insertRole("ZETA");
		insertRole("ALPHA");
		insertRole("MIDDLE");

		getRoles("?page=2&pageSize=2")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].name").value(contains("SUPER_ADMIN", "ZETA")))
				.andExpect(jsonPath("$.pagination.page").value(2))
				.andExpect(jsonPath("$.pagination.pageSize").value(2))
				.andExpect(jsonPath("$.pagination.total").value(4));
	}

	@Test
	void pageSizeOver100Is400() throws Exception {
		getRoles("?pageSize=101")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	// ---- GET /roles/{id} ----

	@Test
	void roleHasEveryField() throws Exception {
		UUID superAdminId = jdbc.queryForObject("SELECT id FROM roles WHERE name = 'SUPER_ADMIN'", UUID.class);

		getRoles("/" + superAdminId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(superAdminId.toString()))
				.andExpect(jsonPath("$.data.name").value("SUPER_ADMIN"))
				.andExpect(jsonPath("$.data.displayName").value("Super Admin"))
				.andExpect(jsonPath("$.data.description").value("Full access to the admin API."))
				.andExpect(jsonPath("$.data.isSystem").value(true))
				.andExpect(jsonPath("$.data.createdAt").isNotEmpty())
				.andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
				.andExpect(jsonPath("$.pagination").doesNotExist());
	}

	@Test
	void roleWithoutDescriptionHasNull() throws Exception {
		UUID id = insertRole("ALPHA");

		getRoles("/" + id)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.description").value(nullValue()))
				.andExpect(jsonPath("$.data.isSystem").value(false));
	}

	@Test
	void unknownRoleIs404() throws Exception {
		getRoles("/00000000-0000-0000-0000-000000000001")
				.andExpect(status().isNotFound())
				.andExpect(content().json(errorJson("ROLE_NOT_FOUND",
						"No role with id 00000000-0000-0000-0000-000000000001."), JsonCompareMode.STRICT));
	}

	@Test
	void roleIdThatIsNotAUuidIs400() throws Exception {
		getRoles("/abc")
				.andExpect(status().isBadRequest())
				.andExpect(content().json(errorJson("VALIDATION_ERROR", "Invalid request: id has an invalid value."),
						JsonCompareMode.STRICT));
	}

	// ---- helpers ----

	private ResultActions getRoles(String rest) throws Exception {
		return mockMvc.perform(get("/admin/v1/roles" + rest).header("Authorization", "Bearer " + tokens.forUser("kc-admin")));
	}

	private UUID insertRole(String name) {
		return jdbc.queryForObject("INSERT INTO roles (name, display_name) VALUES (?, ?) RETURNING id",
				UUID.class, name, name);
	}

	private String errorJson(String code, String message) throws Exception {
		return objectMapper.writeValueAsString(Map.of("error", Map.of("code", code, "message", message)));
	}
}
