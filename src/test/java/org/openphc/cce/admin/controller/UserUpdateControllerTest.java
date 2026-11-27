package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditAssert;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

/**
 * PUT /admin/v1/users/{id} called the way a client calls it. Each test runs in a transaction that is rolled
 * back. The Keycloak failure cases need real commits: UserUpdateFailureTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class UserUpdateControllerTest {

	// Rows are inserted with this updated_at, so a test can tell whether the edit moved it
	private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");

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

	@Autowired
	private EntityManager entityManager;

	private UUID alice;

	@BeforeEach
	void insertUsers() {
		insertUser("admin", "admin@x.com", "ACTIVE", null);
		alice = insertUser("alice", "alice@example.org", "ACTIVE", "KE-001");
	}

	@Test
	void newEmailAndNamesGoToBothSides() throws Exception {
		putUser(alice, body("Alice.O@Example.ORG", "Alicia", "Otieno-Kamau", "KE-001", null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(alice.toString()))
				.andExpect(jsonPath("$.data.username").value("alice"))
				.andExpect(jsonPath("$.data.email").value("alice.o@example.org"))
				.andExpect(jsonPath("$.data.firstName").value("Alicia"))
				.andExpect(jsonPath("$.data.lastName").value("Otieno-Kamau"))
				.andExpect(jsonPath("$.data.status").value("ACTIVE"))
				.andExpect(jsonPath("$.data.facilityScope").value("KE-001"));

		verify(keycloakClient).updateUser("kc-alice", "alice.o@example.org", "Alicia", "Otieno-Kamau");
		verifyNoMoreInteractions(keycloakClient);
		entityManager.flush();
		Map<String, Object> row = row(alice);
		assertThat(row).containsEntry("email", "alice.o@example.org").containsEntry("first_name", "Alicia")
				.containsEntry("last_name", "Otieno-Kamau");
		assertThat(((Timestamp) row.get("updated_at")).toInstant()).isAfter(OLD);

		AuditAssert.exactlyOneRow(jdbc, AuditAction.USER_UPDATED, alice.toString());
		Map<String, Object> audit = jdbc.queryForMap("SELECT before::text AS before, after::text AS after"
				+ " FROM audit_log WHERE action = 'USER_UPDATED'");
		JSONAssert.assertEquals("{\"firstName\":\"Alice\",\"lastName\":\"Otieno\",\"email\":\"alice@example.org\","
				+ "\"facilityScope\":\"KE-001\"}", (String) audit.get("before"), true);
		JSONAssert.assertEquals("{\"firstName\":\"Alicia\",\"lastName\":\"Otieno-Kamau\","
				+ "\"email\":\"alice.o@example.org\",\"facilityScope\":\"KE-001\"}", (String) audit.get("after"), true);
	}

	@Test
	void onlyFacilityScopeChangedCallsNoKeycloak() throws Exception {
		putUser(alice, body("alice@example.org", "Alice", "Otieno", "KE-002", null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.facilityScope").value("KE-002"));

		verifyNoInteractions(keycloakClient);
		entityManager.flush();
		assertThat(row(alice)).containsEntry("facility_scope", "KE-002");
		AuditAssert.exactlyOneRow(jdbc, AuditAction.USER_UPDATED, alice.toString());
	}

	@Test
	void nullFacilityScopeClearsIt() throws Exception {
		putUser(alice, body("alice@example.org", "Alice", "Otieno", null, null))
				.andExpect(status().isOk());

		entityManager.flush();
		assertThat(row(alice).get("facility_scope")).isNull();
		verifyNoInteractions(keycloakClient);
	}

	@Test
	void sameValuesWriteNothing() throws Exception {
		// Capitals and spaces in the email are cleaned first, so this is the same profile
		putUser(alice, body(" ALICE@example.org ", "Alice", "Otieno", "KE-001", "Alice"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value("alice@example.org"));

		verifyNoInteractions(keycloakClient);
		entityManager.flush();
		assertThat(((Timestamp) row(alice).get("updated_at")).toInstant()).isEqualTo(OLD);
		assertThat(auditRows("USER_UPDATED")).isZero();
	}

	@Test
	void missingOrBlankFieldsAreRefused() throws Exception {
		expectRefused(alice, body(null, "Alice", "Otieno", null, null), 400, "VALIDATION_ERROR",
				"Invalid request: email must not be blank.");
		expectRefused(alice, body("alice@example.org", " ", "Otieno", null, null), 400, "VALIDATION_ERROR",
				"Invalid request: firstName must not be blank.");
		expectRefused(alice, body("alice@example.org", "Alice", "a".repeat(256), null, null), 400,
				"VALIDATION_ERROR", "Invalid request: lastName size must be between 0 and 255.");
	}

	@Test
	void differentUsernameIsRefused() throws Exception {
		expectRefused(alice, body("alice@example.org", "Alicia", "Otieno", null, "bob"), 400, "VALIDATION_ERROR",
				"Username cannot be changed (it is the user's Keycloak login). Nothing was changed.");
	}

	@Test
	void unknownUserIsNotFound() throws Exception {
		UUID unknown = UUID.randomUUID();

		expectRefused(unknown, body("x@example.org", "X", "Y", null, null), 404, "USER_NOT_FOUND",
				"No user with id " + unknown + ".");
	}

	@Test
	void emailOfAnotherUserIsAConflict() throws Exception {
		insertUser("bob", "Bob@Example.org", "ACTIVE", null);

		expectRefused(alice, body("bob@example.org", "Alice", "Otieno", "KE-001", null), 409, "EMAIL_IN_USE",
				"Email bob@example.org is already used by another user.");
	}

	@Test
	void inactiveUserCanBeEdited() throws Exception {
		UUID carol = insertUser("carol", "carol@example.org", "INACTIVE", null);

		putUser(carol, body("carol.new@example.org", "Carol", "Otieno", null, null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("INACTIVE"));

		verify(keycloakClient).updateUser("kc-carol", "carol.new@example.org", "Carol", "Otieno");
	}

	/** The given error, Keycloak never called, the row as it was and no audit row. */
	private void expectRefused(UUID id, String body, int status, String code, String message) throws Exception {
		putUser(id, body)
				.andExpect(status().is(status))
				.andExpect(jsonPath("$.error.code").value(code))
				.andExpect(jsonPath("$.error.message").value(message));

		verifyNoInteractions(keycloakClient);
		entityManager.flush();
		assertThat(row(alice)).containsEntry("email", "alice@example.org").containsEntry("first_name", "Alice");
		assertThat(auditRows("USER_UPDATED")).isZero();
	}

	private ResultActions putUser(UUID id, String body) throws Exception {
		return mockMvc.perform(put("/admin/v1/users/" + id)
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	/** A PUT body; a null field is left out. */
	private String body(String email, String firstName, String lastName, String facilityScope, String username) {
		List<String> fields = new ArrayList<>();
		addField(fields, "email", email);
		addField(fields, "firstName", firstName);
		addField(fields, "lastName", lastName);
		addField(fields, "facilityScope", facilityScope);
		addField(fields, "username", username);
		return "{" + String.join(",", fields) + "}";
	}

	private void addField(List<String> fields, String name, String value) {
		if (value != null) {
			fields.add("\"" + name + "\":\"" + value + "\"");
		}
	}

	private UUID insertUser(String username, String email, String status, String facilityScope) {
		return jdbc.queryForObject("INSERT INTO users (keycloak_id, username, email, first_name, last_name, status,"
				+ " facility_scope, updated_at) VALUES (?, ?, ?, 'Alice', 'Otieno', ?, ?, ?) RETURNING id", UUID.class,
				"kc-" + username, username, email, status, facilityScope, Timestamp.from(OLD));
	}

	private Map<String, Object> row(UUID id) {
		return jdbc.queryForMap("SELECT email, first_name, last_name, facility_scope, updated_at FROM users"
				+ " WHERE id = ?", id);
	}

	private int auditRows(String action) {
		return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Integer.class, action);
	}
}
