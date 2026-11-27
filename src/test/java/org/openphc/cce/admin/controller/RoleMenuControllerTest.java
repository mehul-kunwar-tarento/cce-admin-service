package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditAssert;
import org.openphc.cce.admin.client.GatewaySyncClient;
import org.openphc.cce.admin.client.KeycloakClient;
import org.openphc.cce.admin.security.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
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

import jakarta.persistence.EntityManager;

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

	@Autowired
	private EntityManager entityManager;

	// No gateway or Keycloak in tests; role menus must never call either
	@MockitoBean
	private GatewaySyncClient gatewaySyncClient;

	@MockitoBean
	private KeycloakClient keycloakClient;

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

	// ---- PUT /roles/{id}/menus: saved ----

	@Test
	void replaceChangesOnlyTheDifference() throws Exception {
		UUID id = insertRole("NURSE");
		// An old date, so a link that was deleted and inserted again would show a new one
		Timestamp longAgo = Timestamp.valueOf("2020-01-01 00:00:00");
		linkMenuAt(id, "dashboard", longAgo);
		linkMenu(id, "exports");

		putMenus(id.toString(), menuIdsBody("dashboard", "compliance"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.menus[*].key").value(contains("dashboard", "compliance")))
				.andExpect(content().json("{\"data\": {\"warnings\": []}}"));
		sendPendingWrites();

		assertLinks(id, "compliance", "dashboard");
		assertThat(linkCreatedAt(id, "dashboard")).isEqualTo(longAgo);
		AuditAssert.exactlyOneRow(jdbc, AuditAction.ROLE_MENUS_UPDATED, id.toString());
		Map<String, Object> audit = jdbc.queryForMap("SELECT entity_type, before::text AS before,"
				+ " after::text AS after FROM audit_log WHERE entity_id = ?", id.toString());
		assertThat(audit.get("entity_type")).isEqualTo("ROLE");
		assertThat(objectMapper.readTree((String) audit.get("before")))
				.isEqualTo(objectMapper.readTree("{\"menus\": [\"dashboard\", \"exports\"]}"));
		assertThat(objectMapper.readTree((String) audit.get("after")))
				.isEqualTo(objectMapper.readTree("{\"menus\": [\"compliance\", \"dashboard\"]}"));
	}

	@Test
	void detailWithoutItsParentIsSavedWithAWarning() throws Exception {
		UUID id = insertRole("NURSE");

		putMenus(id.toString(), menuIdsBody("patient-detail"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.menus[*].key").value(contains("patient-detail")))
				.andExpect(jsonPath("$.data.warnings").value(contains("DETAIL 'patient-detail' given without its"
						+ " parent 'patients'; users get it only if another of their roles gives 'patients'.")));
		sendPendingWrites();

		assertLinks(id, "patient-detail");
	}

	@Test
	void detailWithItsParentHasNoWarning() throws Exception {
		UUID id = insertRole("NURSE");

		putMenus(id.toString(), menuIdsBody("patients", "patient-detail"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.menus[*].key").value(contains("patients", "patient-detail")))
				.andExpect(content().json("{\"data\": {\"warnings\": []}}"));
	}

	@Test
	void emptyListRemovesEveryMenu() throws Exception {
		UUID id = insertRole("NURSE");
		linkMenu(id, "dashboard");
		linkMenu(id, "exports");

		putMenus(id.toString(), "{\"menuIds\": []}")
				.andExpect(status().isOk())
				.andExpect(content().json("{\"data\": {\"menus\": [], \"warnings\": []}}", JsonCompareMode.STRICT));
		sendPendingWrites();

		assertLinks(id);
		String after = jdbc.queryForObject("SELECT after::text FROM audit_log WHERE entity_id = ?", String.class,
				id.toString());
		assertThat(objectMapper.readTree(after)).isEqualTo(objectMapper.readTree("{\"menus\": []}"));
	}

	@Test
	void duplicateIdsAreSavedOnce() throws Exception {
		UUID id = insertRole("NURSE");

		putMenus(id.toString(), menuIdsBody("dashboard", "dashboard"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.menus[*].key").value(contains("dashboard")));
		sendPendingWrites();

		assertLinks(id, "dashboard");
	}

	@Test
	void sameSetWritesNothing() throws Exception {
		UUID id = insertRole("NURSE");
		linkMenu(id, "dashboard");
		linkMenu(id, "patient-detail");

		// The same set in another order, with one id twice
		putMenus(id.toString(), menuIdsBody("patient-detail", "dashboard", "patient-detail"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.menus[*].key").value(contains("dashboard", "patient-detail")))
				.andExpect(jsonPath("$.data.warnings.length()").value(1));
		sendPendingWrites();

		assertLinks(id, "dashboard", "patient-detail");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class)).isZero();
	}

	@Test
	void inactiveMenuCanBeAssigned() throws Exception {
		jdbc.update("UPDATE menus SET is_active = false WHERE key = 'events'");
		UUID id = insertRole("NURSE");

		putMenus(id.toString(), menuIdsBody("events"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.menus[*].key").value(contains("events")));
		sendPendingWrites();

		assertLinks(id, "events");
	}

	@Test
	void noGatewayOrKeycloakCall() throws Exception {
		UUID id = insertRole("NURSE");
		linkMenu(id, "exports");

		getMenus(id.toString()).andExpect(status().isOk());
		putMenus(id.toString(), menuIdsBody("dashboard", "patient-detail")).andExpect(status().isOk());
		putMenus(id.toString(), menuIdsBody("dashboard", "patient-detail")).andExpect(status().isOk());

		verifyNoInteractions(gatewaySyncClient);
		verifyNoInteractions(keycloakClient);
	}

	// ---- PUT /roles/{id}/menus: rejected ----

	@Test
	void missingMenuIdsIs400() throws Exception {
		UUID id = insertRole("NURSE");
		linkMenu(id, "dashboard");

		expectPutRejected(id.toString(), "{}", 400, "VALIDATION_ERROR", "Invalid request: menuIds must not be null.");
		assertLinks(id, "dashboard");
	}

	@Test
	void nullInsideTheListIs400() throws Exception {
		UUID id = insertRole("NURSE");
		linkMenu(id, "dashboard");
		String body = objectMapper.writeValueAsString(
				Map.of("menuIds", Arrays.asList(menuId("dashboard").toString(), null)));

		expectPutRejected(id.toString(), body, 400, "VALIDATION_ERROR",
				"Invalid request: menuIds[1] must not be null.");
		assertLinks(id, "dashboard");
	}

	@Test
	void unknownRoleIs404OnPut() throws Exception {
		expectPutRejected("00000000-0000-0000-0000-000000000001", menuIdsBody("dashboard"), 404, "ROLE_NOT_FOUND",
				"No role with id 00000000-0000-0000-0000-000000000001.");
	}

	@Test
	void superAdminIs409() throws Exception {
		UUID id = superAdminId();

		expectPutRejected(id.toString(), menuIdsBody("dashboard"), 409, "SYSTEM_ROLE_IMMUTABLE",
				"'SUPER_ADMIN' is a system role and can't be changed or deleted.");
		assertLinks(id);
	}

	@Test
	void superAdminWithAnUnknownIdIs409NotA400() throws Exception {
		UUID id = superAdminId();
		String body = objectMapper.writeValueAsString(
				Map.of("menuIds", List.of("00000000-0000-0000-0000-000000000001")));

		expectPutRejected(id.toString(), body, 409, "SYSTEM_ROLE_IMMUTABLE",
				"'SUPER_ADMIN' is a system role and can't be changed or deleted.");
	}

	@Test
	void unknownMenuIdsAreNamed() throws Exception {
		UUID id = insertRole("NURSE");
		linkMenu(id, "dashboard");
		// One of them twice, and four that a HashSet gives back out of text order: the message names each
		// once, sorted as text
		String body = objectMapper.writeValueAsString(Map.of("menuIds", List.of(
				"ffffffff-0000-0000-0000-000000000002",
				menuId("compliance").toString(),
				"aaaaaaaa-0000-0000-0000-000000000004",
				"00000000-0000-0000-0000-000000000001",
				"11111111-0000-0000-0000-000000000003",
				"ffffffff-0000-0000-0000-000000000002")));

		expectPutRejected(id.toString(), body, 400, "VALIDATION_ERROR", "Unknown menu id(s):"
				+ " 00000000-0000-0000-0000-000000000001, 11111111-0000-0000-0000-000000000003,"
				+ " aaaaaaaa-0000-0000-0000-000000000004, ffffffff-0000-0000-0000-000000000002. Nothing was changed.");
		assertLinks(id, "dashboard");
	}

	@Test
	void menuIdThatIsNotAUuidIs400() throws Exception {
		UUID id = insertRole("NURSE");

		expectPutRejected(id.toString(), "{\"menuIds\": [\"abc\"]}", 400, "VALIDATION_ERROR",
				"Request body is not valid JSON, or a field has the wrong type.");
	}

	@Test
	void roleIdThatIsNotAUuidIs400OnPut() throws Exception {
		expectPutRejected("abc", menuIdsBody("dashboard"), 400, "VALIDATION_ERROR",
				"Invalid request: id has an invalid value.");
	}

	// ---- helpers ----

	private ResultActions getMenus(String roleId) throws Exception {
		return mockMvc.perform(get("/admin/v1/roles/" + roleId + "/menus")
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin")));
	}

	private ResultActions putMenus(String roleId, String body) throws Exception {
		return mockMvc.perform(put("/admin/v1/roles/" + roleId + "/menus")
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	/** The given error, and no audit row. */
	private void expectPutRejected(String roleId, String body, int status, String code, String message)
			throws Exception {
		putMenus(roleId, body)
				.andExpect(status().is(status))
				.andExpect(content().json(errorJson(code, message), JsonCompareMode.STRICT));
		sendPendingWrites();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class)).isZero();
	}

	/** The role has exactly these menus, A to Z by key. */
	private void assertLinks(UUID roleId, String... keys) {
		List<String> actual = jdbc.queryForList("SELECT m.key FROM role_menus rm JOIN menus m ON m.id = rm.menu_id"
				+ " WHERE rm.role_id = ? ORDER BY m.key", String.class, roleId);
		assertThat(actual).containsExactly(keys);
	}

	private String menuIdsBody(String... keys) throws Exception {
		List<String> ids = new ArrayList<>();
		for (String key : keys) {
			ids.add(menuId(key).toString());
		}
		return objectMapper.writeValueAsString(Map.of("menuIds", ids));
	}

	private UUID superAdminId() {
		return jdbc.queryForObject("SELECT id FROM roles WHERE name = 'SUPER_ADMIN'", UUID.class);
	}

	private void sendPendingWrites() {
		entityManager.flush();
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

	private void linkMenuAt(UUID roleId, String menuKey, Timestamp createdAt) {
		jdbc.update("INSERT INTO role_menus (role_id, menu_id, created_at) SELECT ?, id, ? FROM menus WHERE key = ?",
				roleId, createdAt, menuKey);
	}

	private Timestamp linkCreatedAt(UUID roleId, String menuKey) {
		return jdbc.queryForObject("SELECT rm.created_at FROM role_menus rm JOIN menus m ON m.id = rm.menu_id"
				+ " WHERE rm.role_id = ? AND m.key = ?", Timestamp.class, roleId, menuKey);
	}

	private UUID menuId(String key) {
		return jdbc.queryForObject("SELECT id FROM menus WHERE key = ?", UUID.class, key);
	}

	private String errorJson(String code, String message) throws Exception {
		return objectMapper.writeValueAsString(Map.of("error", Map.of("code", code, "message", message)));
	}
}
