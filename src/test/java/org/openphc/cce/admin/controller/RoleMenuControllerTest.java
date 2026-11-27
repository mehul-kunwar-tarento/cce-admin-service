package org.openphc.cce.admin.controller;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.client.GatewaySyncClient;
import org.openphc.cce.admin.security.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * /admin/v1/roles/{id}/menus called the way a client calls it: a signed token, an ACTIVE local user, the
 * real seed. Each test runs in a transaction that is rolled back, so tests never see each other's rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class RoleMenuControllerTest {

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

	// No gateway in tests
	@MockitoBean
	private GatewaySyncClient gatewaySyncClient;

	@BeforeEach
	void insertAdmin() {
		jdbc.update("INSERT INTO users (keycloak_id, username, email, status) VALUES (?, ?, ?, ?)",
				"kc-admin", "admin", "admin@x.com", "ACTIVE");
	}

	// ---- GET /roles/{id}/menus ----

	@Test
	void menusAreInSidebarOrder() throws Exception {
		UUID id = insertRole("NURSE");
		linkMenu(id, "exports");
		linkMenu(id, "patient-detail");
		linkMenu(id, "dashboard");
		linkMenu(id, "patients");
		linkMenu(id, "compliance");

		getMenus(id.toString())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].key").value(
						contains("dashboard", "compliance", "patients", "patient-detail", "exports")));
	}

	@Test
	void sameSortOrderIsOrderedByKey() throws Exception {
		insertNav("zz-b", 500);
		insertNav("zz-a", 500);
		UUID id = insertRole("NURSE");
		linkMenu(id, "zz-b");
		linkMenu(id, "zz-a");

		getMenus(id.toString())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].key").value(contains("zz-a", "zz-b")));
	}

	@Test
	void itemHasEveryField() throws Exception {
		UUID id = insertRole("NURSE");
		linkMenu(id, "patient-detail");
		UUID menuId = menuId("patient-detail");
		UUID parentId = menuId("patients");

		getMenus(id.toString())
				.andExpect(status().isOk())
				.andExpect(content().json(objectMapper.writeValueAsString(Map.of("data", List.of(Map.of(
						"id", menuId.toString(),
						"key", "patient-detail",
						"label", "Patient detail",
						"type", "DETAIL",
						"parentId", parentId.toString())))), JsonCompareMode.STRICT));
	}

	@Test
	void roleWithoutMenusHasEmptyList() throws Exception {
		UUID superAdminId = jdbc.queryForObject("SELECT id FROM roles WHERE name = 'SUPER_ADMIN'", UUID.class);

		getMenus(superAdminId.toString())
				.andExpect(status().isOk())
				.andExpect(content().json("{\"data\": []}", JsonCompareMode.STRICT));
	}

	@Test
	void unknownRoleIs404() throws Exception {
		getMenus("00000000-0000-0000-0000-000000000001")
				.andExpect(status().isNotFound())
				.andExpect(content().json(errorJson("ROLE_NOT_FOUND",
						"No role with id 00000000-0000-0000-0000-000000000001."), JsonCompareMode.STRICT));
	}

	@Test
	void roleIdThatIsNotAUuidIs400() throws Exception {
		getMenus("abc")
				.andExpect(status().isBadRequest())
				.andExpect(content().json(errorJson("VALIDATION_ERROR", "Invalid request: id has an invalid value."),
						JsonCompareMode.STRICT));
	}

	// ---- helpers ----

	private ResultActions getMenus(String roleId) throws Exception {
		return mockMvc.perform(get("/admin/v1/roles/" + roleId + "/menus")
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin")));
	}

	private UUID insertRole(String name) {
		return jdbc.queryForObject("INSERT INTO roles (name, display_name) VALUES (?, ?) RETURNING id",
				UUID.class, name, name);
	}

	private void insertNav(String key, int sortOrder) {
		jdbc.update("INSERT INTO menus (key, label, path, type, sort_order) VALUES (?, ?, ?, 'NAV', ?)",
				key, key, "/" + key, sortOrder);
	}

	private void linkMenu(UUID roleId, String menuKey) {
		jdbc.update("INSERT INTO role_menus (role_id, menu_id) SELECT ?, id FROM menus WHERE key = ?", roleId, menuKey);
	}

	private UUID menuId(String key) {
		return jdbc.queryForObject("SELECT id FROM menus WHERE key = ?", UUID.class, key);
	}

	private String errorJson(String code, String message) throws Exception {
		return objectMapper.writeValueAsString(Map.of("error", Map.of("code", code, "message", message)));
	}
}
