package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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

	// No gateway or Keycloak in tests
	@MockitoBean
	private GatewaySyncClient gatewaySyncClient;

	@MockitoBean
	private KeycloakClient keycloakClient;

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
