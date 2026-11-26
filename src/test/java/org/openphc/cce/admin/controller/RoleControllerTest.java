package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditAssert;
import org.openphc.cce.admin.client.GatewaySyncClient;
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

	@Autowired
	private EntityManager entityManager;

	// No gateway in tests; each test says what the sync does, and checks whether it was called
	@MockitoBean
	private GatewaySyncClient gatewaySyncClient;

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

	// ---- POST /roles: accepted ----

	@Test
	void createIs201WithOnePermissionRowAndOneAuditRow() throws Exception {
		String body = postRole(role("FACILITY_ADMIN"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.name").value("FACILITY_ADMIN"))
				.andExpect(jsonPath("$.data.displayName").value("Facility admin"))
				.andExpect(jsonPath("$.data.description").value("Runs one facility"))
				.andExpect(jsonPath("$.data.isSystem").value(false))
				.andExpect(jsonPath("$.data.createdAt").isNotEmpty())
				.andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
				.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(objectMapper.readTree(body).path("data").path("id").asText());
		sendPendingWrites();

		List<Map<String, Object>> rows = jdbc.queryForList("SELECT http_method, uri_pattern FROM api_permissions"
				+ " WHERE permission_name = 'FACILITY_ADMIN'");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("http_method")).isEqualTo("GET");
		assertThat(rows.get(0).get("uri_pattern")).isEqualTo("/admin/v1/me/menus");

		AuditAssert.exactlyOneRow(jdbc, AuditAction.ROLE_CREATED, id.toString());
		Map<String, Object> audit = jdbc.queryForMap("SELECT entity_type, before, after -> 'role' ->> 'name' AS role_name,"
				+ " after -> 'role' ->> 'isSystem' AS is_system, after -> 'permissions' -> 0 ->> 'uriPattern' AS uri"
				+ " FROM audit_log WHERE entity_id = ?", id.toString());
		assertThat(audit.get("entity_type")).isEqualTo("ROLE");
		assertThat(audit.get("before")).isNull();
		assertThat(audit.get("role_name")).isEqualTo("FACILITY_ADMIN");
		assertThat(audit.get("is_system")).isEqualTo("false");
		assertThat(audit.get("uri")).isEqualTo("/admin/v1/me/menus");

		verify(gatewaySyncClient, times(1)).sync(id);
	}

	@Test
	void isSystemInTheBodyIsIgnored() throws Exception {
		Map<String, Object> body = role("FACILITY_ADMIN");
		body.put("isSystem", true);

		postRole(body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.isSystem").value(false));
		Boolean isSystem = jdbc.queryForObject("SELECT is_system FROM roles WHERE name = 'FACILITY_ADMIN'", Boolean.class);
		assertThat(isSystem).isFalse();
	}

	@Test
	void blankDescriptionIsStoredAsNone() throws Exception {
		Map<String, Object> body = role("FACILITY_ADMIN");
		body.put("description", "   ");

		postRole(body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.description").value(nullValue()));
	}

	// ---- POST /roles: rejected, nothing saved and no sync ----

	@Test
	void lowercaseNameIs400() throws Exception {
		expectCreateRejected(400, role("facility_admin"), "VALIDATION_ERROR",
				"Invalid request: name must be UPPER_SNAKE_CASE, starting with a letter (e.g. FACILITY_ADMIN).");
	}

	@Test
	void missingDisplayNameIs400() throws Exception {
		Map<String, Object> body = role("FACILITY_ADMIN");
		body.remove("displayName");
		expectCreateRejected(400, body, "VALIDATION_ERROR", "Invalid request: displayName must not be blank.");
	}

	@Test
	void blankDisplayNameIs400() throws Exception {
		Map<String, Object> body = role("FACILITY_ADMIN");
		body.put("displayName", "   ");
		expectCreateRejected(400, body, "VALIDATION_ERROR", "Invalid request: displayName must not be blank.");
	}

	@Test
	void tooLongNameIs400() throws Exception {
		expectCreateRejected(400, role("A".repeat(101)), "VALIDATION_ERROR",
				"Invalid request: name size must be between 0 and 100.");
	}

	@Test
	void tooLongDisplayNameIs400() throws Exception {
		Map<String, Object> body = role("FACILITY_ADMIN");
		body.put("displayName", "x".repeat(256));
		expectCreateRejected(400, body, "VALIDATION_ERROR", "Invalid request: displayName size must be between 0 and 255.");
	}

	@Test
	void existingRoleNameIs409() throws Exception {
		expectCreateRejected(409, role("SUPER_ADMIN"), "DUPLICATE_ROLE_NAME",
				"A role named 'SUPER_ADMIN' already exists.");
	}

	@Test
	void nameUsedByAPermissionSetIs409() throws Exception {
		jdbc.update("INSERT INTO api_permissions (permission_name, http_method, uri_pattern)"
				+ " VALUES ('COLLECTOR_EVENTS_WRITE', 'POST', '/collector/v1/events')");

		expectCreateRejected(409, role("COLLECTOR_EVENTS_WRITE"), "DUPLICATE_ROLE_NAME",
				"This name is already used by an existing permission set in the gateway. Choose another name.");
	}

	// ---- PUT /roles/{id}: accepted, never syncs ----

	@Test
	void updateIs200WithBeforeAndAfter() throws Exception {
		UUID id = insertRoleWithDescription("FACILITY_ADMIN", "Facility admin", "Runs one facility");
		Timestamp updatedAtBefore = updatedAt(id);

		putRole(id, edit("FACILITY_ADMIN", "Site admin", "Runs one site"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("FACILITY_ADMIN"))
				.andExpect(jsonPath("$.data.displayName").value("Site admin"))
				.andExpect(jsonPath("$.data.description").value("Runs one site"));
		sendPendingWrites();

		AuditAssert.exactlyOneRow(jdbc, AuditAction.ROLE_UPDATED, id.toString());
		Map<String, Object> audit = jdbc.queryForMap("SELECT entity_type, before ->> 'displayName' AS before_name,"
				+ " after ->> 'displayName' AS after_name, after ->> 'description' AS after_description"
				+ " FROM audit_log WHERE entity_id = ?", id.toString());
		assertThat(audit.get("entity_type")).isEqualTo("ROLE");
		assertThat(audit.get("before_name")).isEqualTo("Facility admin");
		assertThat(audit.get("after_name")).isEqualTo("Site admin");
		assertThat(audit.get("after_description")).isEqualTo("Runs one site");
		assertThat(updatedAt(id)).isAfter(updatedAtBefore);
		verifyNoInteractions(gatewaySyncClient);
	}

	@Test
	void nameLeftOutIs200() throws Exception {
		UUID id = insertRoleWithDescription("FACILITY_ADMIN", "Facility admin", null);

		putRole(id, edit(null, "Site admin", null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.displayName").value("Site admin"));
		verifyNoInteractions(gatewaySyncClient);
	}

	@Test
	void sameNameIs200() throws Exception {
		UUID id = insertRoleWithDescription("FACILITY_ADMIN", "Facility admin", null);

		putRole(id, edit("FACILITY_ADMIN", "Site admin", null))
				.andExpect(status().isOk());
		verifyNoInteractions(gatewaySyncClient);
	}

	@Test
	void missingDescriptionClearsIt() throws Exception {
		UUID id = insertRoleWithDescription("FACILITY_ADMIN", "Facility admin", "Runs one facility");

		putRole(id, edit("FACILITY_ADMIN", "Facility admin", null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.description").value(nullValue()));
		sendPendingWrites();

		String description = jdbc.queryForObject("SELECT description FROM roles WHERE id = ?", String.class, id);
		assertThat(description).isNull();
		assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'ROLE_UPDATED'")).isEqualTo(1);
	}

	@Test
	void sameValuesWriteNothing() throws Exception {
		UUID id = insertRoleWithDescription("FACILITY_ADMIN", "Facility admin", "Runs one facility");
		Timestamp updatedAtBefore = updatedAt(id);

		putRole(id, edit("FACILITY_ADMIN", "Facility admin", "Runs one facility"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.displayName").value("Facility admin"));
		sendPendingWrites();

		assertThat(count("SELECT count(*) FROM audit_log")).isZero();
		assertThat(updatedAt(id)).isEqualTo(updatedAtBefore);
		verifyNoInteractions(gatewaySyncClient);
	}

	// ---- PUT /roles/{id}: rejected, nothing changed ----

	@Test
	void unknownRoleIs404OnPut() throws Exception {
		putRole(UUID.fromString("00000000-0000-0000-0000-000000000001"), edit(null, "Site admin", null))
				.andExpect(status().isNotFound())
				.andExpect(content().json(errorJson("ROLE_NOT_FOUND",
						"No role with id 00000000-0000-0000-0000-000000000001."), JsonCompareMode.STRICT));
	}

	@Test
	void superAdminIs409() throws Exception {
		expectUpdateRejected(superAdminId(), edit(null, "Admins", null), 409, "SYSTEM_ROLE_IMMUTABLE",
				"'SUPER_ADMIN' is a system role and can't be changed or deleted.");
	}

	@Test
	void superAdminRenameIs409NotA400() throws Exception {
		expectUpdateRejected(superAdminId(), edit("ADMIN", "Super Admin", null), 409, "SYSTEM_ROLE_IMMUTABLE",
				"'SUPER_ADMIN' is a system role and can't be changed or deleted.");
	}

	@Test
	void differentNameIs400() throws Exception {
		UUID id = insertRoleWithDescription("FACILITY_ADMIN", "Facility admin", null);
		expectUpdateRejected(id, edit("SITE_ADMIN", "Facility admin", null), 400, "VALIDATION_ERROR",
				"Role name cannot be changed (it is used in Keycloak and the gateway). Only displayName and"
						+ " description can be edited. To use a different name, create a new role. Nothing was changed.");
	}

	@Test
	void missingDisplayNameIs400OnPut() throws Exception {
		UUID id = insertRoleWithDescription("FACILITY_ADMIN", "Facility admin", null);
		expectUpdateRejected(id, edit("FACILITY_ADMIN", null, null), 400, "VALIDATION_ERROR",
				"Invalid request: displayName must not be blank.");
	}

	@Test
	void tooLongDisplayNameIs400OnPut() throws Exception {
		UUID id = insertRoleWithDescription("FACILITY_ADMIN", "Facility admin", null);
		expectUpdateRejected(id, edit("FACILITY_ADMIN", "x".repeat(256), null), 400, "VALIDATION_ERROR",
				"Invalid request: displayName size must be between 0 and 255.");
	}

	// ---- helpers ----

	private ResultActions getRoles(String rest) throws Exception {
		return mockMvc.perform(get("/admin/v1/roles" + rest).header("Authorization", "Bearer " + tokens.forUser("kc-admin")));
	}

	private ResultActions putRole(UUID id, Map<String, Object> body) throws Exception {
		return mockMvc.perform(put("/admin/v1/roles/" + id)
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)));
	}

	/** The given error; the role row is unchanged, no audit row, no sync. */
	private void expectUpdateRejected(UUID id, Map<String, Object> body, int status, String code, String message)
			throws Exception {
		Map<String, Object> rowBefore = jdbc.queryForMap("SELECT * FROM roles WHERE id = ?", id);

		putRole(id, body)
				.andExpect(status().is(status))
				.andExpect(content().json(errorJson(code, message), JsonCompareMode.STRICT));
		sendPendingWrites();

		assertThat(jdbc.queryForMap("SELECT * FROM roles WHERE id = ?", id)).isEqualTo(rowBefore);
		assertThat(count("SELECT count(*) FROM audit_log")).isZero();
		verifyNoInteractions(gatewaySyncClient);
	}

	/** A PUT body; a null name or description is left out. */
	private Map<String, Object> edit(String name, String displayName, String description) {
		Map<String, Object> body = new LinkedHashMap<>();
		if (name != null) {
			body.put("name", name);
		}
		body.put("displayName", displayName);
		if (description != null) {
			body.put("description", description);
		}
		return body;
	}

	private UUID insertRoleWithDescription(String name, String displayName, String description) {
		return jdbc.queryForObject("INSERT INTO roles (name, display_name, description) VALUES (?, ?, ?) RETURNING id",
				UUID.class, name, displayName, description);
	}

	private UUID superAdminId() {
		return jdbc.queryForObject("SELECT id FROM roles WHERE name = 'SUPER_ADMIN'", UUID.class);
	}

	private Timestamp updatedAt(UUID id) {
		return jdbc.queryForObject("SELECT updated_at FROM roles WHERE id = ?", Timestamp.class, id);
	}

	private ResultActions postRole(Map<String, Object> body) throws Exception {
		return mockMvc.perform(post("/admin/v1/roles")
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)));
	}

	/** The given error, and nothing saved: still only SUPER_ADMIN, its rows, no audit row, no sync. */
	private void expectCreateRejected(int status, Map<String, Object> body, String code, String message)
			throws Exception {
		int permissionsBefore = count("SELECT count(*) FROM api_permissions");

		postRole(body)
				.andExpect(status().is(status))
				.andExpect(content().json(errorJson(code, message), JsonCompareMode.STRICT));
		sendPendingWrites();

		assertThat(count("SELECT count(*) FROM roles")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM api_permissions")).isEqualTo(permissionsBefore);
		assertThat(count("SELECT count(*) FROM audit_log")).isZero();
		verifyNoInteractions(gatewaySyncClient);
	}

	/** A valid create body; tests change or remove fields from it. */
	private Map<String, Object> role(String name) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("displayName", "Facility admin");
		body.put("description", "Runs one facility");
		return body;
	}

	/** The test's transaction never commits, so plain SQL sees Hibernate's queued writes only after a flush. */
	private void sendPendingWrites() {
		entityManager.flush();
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	private UUID insertRole(String name) {
		return jdbc.queryForObject("INSERT INTO roles (name, display_name) VALUES (?, ?) RETURNING id",
				UUID.class, name, name);
	}

	private String errorJson(String code, String message) throws Exception {
		return objectMapper.writeValueAsString(Map.of("error", Map.of("code", code, "message", message)));
	}
}
