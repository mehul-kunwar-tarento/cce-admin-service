package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
import org.skyscreamer.jsonassert.JSONAssert;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

/**
 * POST /admin/v1/users called the way a client calls it: a signed token, an ACTIVE local user, the real seed.
 * Each test runs in a transaction that is rolled back, so tests never see each other's rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class UserCreateControllerTest {

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
	private EntityManager entityManager;

	// No gateway or Keycloak in tests
	@MockitoBean
	private GatewaySyncClient gatewaySyncClient;

	@MockitoBean
	private KeycloakClient keycloakClient;

	private static final String NEW_KEYCLOAK_ID = "9ed584e3-2781-4abe-909e-a4a8a30a0f3e";

	private UUID nurse;

	@BeforeEach
	void insertAdminAndRole() {
		jdbc.update("INSERT INTO users (keycloak_id, username, email, status) VALUES (?, ?, ?, ?)",
				"kc-admin", "admin", "admin@x.com", "ACTIVE");
		nurse = jdbc.queryForObject("INSERT INTO roles (name, display_name) VALUES ('NURSE', 'Nurse') RETURNING id",
				UUID.class);
	}

	// ---- the body ----

	@Test
	void missingUsernameIsRefused() throws Exception {
		expectRefused(body(null, "alice@example.org", "Alice", "Otieno", "[\"" + nurse + "\"]"), 400,
				"VALIDATION_ERROR", "Invalid request: username must not be blank.");
	}

	@Test
	void blankFirstNameIsRefused() throws Exception {
		expectRefused(body("alice", "alice@example.org", " ", "Otieno", "[\"" + nurse + "\"]"), 400,
				"VALIDATION_ERROR", "Invalid request: firstName must not be blank.");
	}

	@Test
	void badEmailIsRefused() throws Exception {
		expectRefused(body("alice", "not-an-email", "Alice", "Otieno", "[\"" + nurse + "\"]"), 400,
				"VALIDATION_ERROR", "Invalid request: email must be a well-formed email address.");
	}

	@Test
	void emptyRoleListIsRefused() throws Exception {
		expectRefused(body("alice", "alice@example.org", "Alice", "Otieno", "[]"), 400, "VALIDATION_ERROR",
				"Invalid request: roleIds must not be empty.");
	}

	@Test
	void nullRoleIdIsRefusedWithItsPosition() throws Exception {
		expectRefused(body("alice", "alice@example.org", "Alice", "Otieno", "[\"" + nurse + "\", null]"), 400,
				"VALIDATION_ERROR", "Invalid request: roleIds[1] must not be null.");
	}

	// ---- the checks in our database ----

	@Test
	void unknownRoleIdsAreAllNamedSortedAsText() throws Exception {
		String first = "00000000-0000-0000-0000-0000000000aa";
		String second = "ffffffff-0000-0000-0000-000000000001";
		String roles = "[\"" + second + "\", \"" + nurse + "\", \"" + first + "\"]";

		expectRefused(body("alice", "alice@example.org", "Alice", "Otieno", roles), 400, "VALIDATION_ERROR",
				"Unknown role id(s): " + first + ", " + second + ". Nothing was created.");
	}

	@Test
	void emailTakenInAnotherCaseIsAConflict() throws Exception {
		insertUser("bob", "Bob@Example.ORG");

		expectRefused(body("robert", "bob@example.org", "Bob", "Otieno", "[\"" + nurse + "\"]"), 409,
				"EMAIL_IN_USE", "Email bob@example.org is already used by another user.");
	}

	@Test
	void usernameTakenIsAConflictAfterLowercasing() throws Exception {
		insertUser("bob", "bob@example.org");

		expectRefused(body(" BOB ", "robert@example.org", "Bob", "Otieno", "[\"" + nurse + "\"]"), 409,
				"USERNAME_IN_USE", "Username bob is already taken.");
	}

	@Test
	void whenBothAreTakenTheEmailIsReported() throws Exception {
		insertUser("bob", "bob@example.org");

		expectRefused(body("bob", "bob@example.org", "Bob", "Otieno", "[\"" + nurse + "\"]"), 409,
				"EMAIL_IN_USE", "Email bob@example.org is already used by another user.");
	}

	// ---- created ----

	@Test
	void newUserIsCreatedInKeycloakThenSaved() throws Exception {
		keycloakCreatesTheAccount();

		String reply = postUser(body("alice", "alice@example.org", "Alice", "Otieno", "[\"" + nurse + "\"]"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.username").value("alice"))
				.andExpect(jsonPath("$.data.email").value("alice@example.org"))
				.andExpect(jsonPath("$.data.status").value("ACTIVE"))
				.andExpect(content().json("{\"data\":{\"roles\":[{\"id\":\"" + nurse
						+ "\",\"name\":\"NURSE\",\"displayName\":\"Nurse\"}]}}", JsonCompareMode.LENIENT))
				.andReturn().getResponse().getContentAsString();
		String id = jsonId(reply);

		// Keycloak: create, then assign, each once, and nothing else (no undo)
		InOrder order = inOrder(keycloakClient);
		order.verify(keycloakClient).createUser("alice", "alice@example.org", "Alice", "Otieno");
		order.verify(keycloakClient).assignRealmRoles(NEW_KEYCLOAK_ID, List.of("NURSE"));
		verifyNoMoreInteractions(keycloakClient);

		entityManager.flush();
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT keycloak_id, username, email, first_name, last_name, status FROM users WHERE id = ?::uuid", id);
		assertThat(row).containsEntry("keycloak_id", NEW_KEYCLOAK_ID).containsEntry("username", "alice")
				.containsEntry("email", "alice@example.org").containsEntry("first_name", "Alice")
				.containsEntry("last_name", "Otieno").containsEntry("status", "ACTIVE");
		assertThat(roleNamesOf(id)).containsExactly("NURSE");

		AuditAssert.exactlyOneRow(jdbc, AuditAction.USER_CREATED, id);
		String after = jdbc.queryForObject(
				"SELECT after::text FROM audit_log WHERE action = 'USER_CREATED' AND entity_id = ?", String.class, id);
		String expected = "{\"id\":\"" + id + "\",\"keycloakId\":\"" + NEW_KEYCLOAK_ID + "\",\"username\":\"alice\","
				+ "\"email\":\"alice@example.org\",\"firstName\":\"Alice\",\"lastName\":\"Otieno\","
				+ "\"status\":\"ACTIVE\",\"facilityScope\":null,\"roles\":[\"NURSE\"]}";
		JSONAssert.assertEquals(expected, after, true);
		assertThat(jdbc.queryForObject("SELECT before IS NULL FROM audit_log WHERE action = 'USER_CREATED'"
				+ " AND entity_id = ?", Boolean.class, id)).isTrue();
	}

	@Test
	void sameRoleTwiceIsGivenOnce() throws Exception {
		keycloakCreatesTheAccount();

		String reply = postUser(body("alice", "alice@example.org", "Alice", "Otieno",
				"[\"" + nurse + "\", \"" + nurse + "\"]"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		verify(keycloakClient).assignRealmRoles(NEW_KEYCLOAK_ID, List.of("NURSE"));
		entityManager.flush();
		assertThat(roleNamesOf(jsonId(reply))).containsExactly("NURSE");
	}

	@Test
	void rolesAreInNameOrderEverywhere() throws Exception {
		UUID pharmacist = insertRole("PHARMACIST");
		keycloakCreatesTheAccount();

		String reply = postUser(body("alice", "alice@example.org", "Alice", "Otieno",
				"[\"" + pharmacist + "\", \"" + nurse + "\"]"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.roles[*].name").value(contains("NURSE", "PHARMACIST")))
				.andReturn().getResponse().getContentAsString();
		String id = jsonId(reply);

		verify(keycloakClient).assignRealmRoles(NEW_KEYCLOAK_ID, List.of("NURSE", "PHARMACIST"));
		entityManager.flush();
		assertThat(jdbc.queryForObject("SELECT after->>'roles' FROM audit_log WHERE action = 'USER_CREATED'"
				+ " AND entity_id = ?", String.class, id)).isEqualTo("[\"NURSE\", \"PHARMACIST\"]");
	}

	@Test
	void passwordInTheBodyGoesNowhere() throws Exception {
		keycloakCreatesTheAccount();
		String body = "{\"username\":\"alice\",\"email\":\"alice@example.org\",\"firstName\":\"Alice\","
				+ "\"lastName\":\"Otieno\",\"roleIds\":[\"" + nurse + "\"],\"password\":\"Secret123!\"}";

		String reply = postUser(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

		// The Keycloak calls take no password at all; these are the only ones made
		verify(keycloakClient).createUser("alice", "alice@example.org", "Alice", "Otieno");
		verify(keycloakClient).assignRealmRoles(NEW_KEYCLOAK_ID, List.of("NURSE"));
		verifyNoMoreInteractions(keycloakClient);
		assertThat(reply).doesNotContain("Secret123!");
		entityManager.flush();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE after::text LIKE '%Secret123!%'",
				Integer.class)).isZero();
	}

	@Test
	void superAdminCanBeGiven() throws Exception {
		UUID superAdmin = jdbc.queryForObject("SELECT id FROM roles WHERE name = 'SUPER_ADMIN'", UUID.class);
		keycloakCreatesTheAccount();

		postUser(body("ramesh", "ramesh@example.org", "Ramesh", "Kumar", "[\"" + superAdmin + "\"]"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.roles[0].name").value("SUPER_ADMIN"));

		verify(keycloakClient).assignRealmRoles(NEW_KEYCLOAK_ID, List.of("SUPER_ADMIN"));
	}

	@Test
	void facilityScopeIsStoredAndAudited() throws Exception {
		keycloakCreatesTheAccount();
		String body = "{\"username\":\"alice\",\"email\":\"alice@example.org\",\"firstName\":\"Alice\","
				+ "\"lastName\":\"Otieno\",\"facilityScope\":\"KE-001\",\"roleIds\":[\"" + nurse + "\"]}";

		String id = jsonId(postUser(body).andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString());

		entityManager.flush();
		assertThat(jdbc.queryForObject("SELECT facility_scope FROM users WHERE id = ?::uuid", String.class, id))
				.isEqualTo("KE-001");
		assertThat(jdbc.queryForObject("SELECT after->>'facilityScope' FROM audit_log WHERE action = 'USER_CREATED'"
				+ " AND entity_id = ?", String.class, id)).isEqualTo("KE-001");
	}

	private void keycloakCreatesTheAccount() {
		when(keycloakClient.createUser(anyString(), anyString(), anyString(), anyString())).thenReturn(NEW_KEYCLOAK_ID);
	}

	private String jsonId(String reply) {
		return JsonPath.read(reply, "$.data.id");
	}

	private List<String> roleNamesOf(String userId) {
		return jdbc.queryForList("SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
				+ " WHERE ur.user_id = ?::uuid ORDER BY r.name", String.class, userId);
	}

	private UUID insertRole(String name) {
		return jdbc.queryForObject("INSERT INTO roles (name, display_name) VALUES (?, ?) RETURNING id", UUID.class,
				name, name);
	}

	private void insertUser(String username, String email) {
		jdbc.update("INSERT INTO users (keycloak_id, username, email, status) VALUES (?, ?, ?, 'ACTIVE')",
				"kc-" + username, username, email);
	}

	/** The given error, Keycloak never called, and no user saved. */
	private void expectRefused(String body, int status, String code, String message) throws Exception {
		Integer usersBefore = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);

		postUser(body)
				.andExpect(status().is(status))
				.andExpect(jsonPath("$.error.code").value(code))
				.andExpect(jsonPath("$.error.message").value(message));

		verifyNoInteractions(keycloakClient);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(usersBefore);
	}

	private ResultActions postUser(String body) throws Exception {
		return mockMvc.perform(post("/admin/v1/users")
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	/** A request body; a null username leaves the field out. */
	private String body(String username, String email, String firstName, String lastName, String roleIds) {
		StringBuilder json = new StringBuilder("{");
		if (username != null) {
			json.append("\"username\":\"").append(username).append("\",");
		}
		json.append("\"email\":\"").append(email).append("\",");
		json.append("\"firstName\":\"").append(firstName).append("\",");
		json.append("\"lastName\":\"").append(lastName).append("\",");
		json.append("\"roleIds\":").append(roleIds);
		json.append("}");
		return json.toString();
	}
}
