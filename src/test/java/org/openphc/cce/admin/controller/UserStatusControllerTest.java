package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
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
import org.skyscreamer.jsonassert.JSONAssert;
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

import jakarta.persistence.EntityManager;

/**
 * PATCH /admin/v1/users/{id}/status called the way a client calls it. Each test runs in a transaction that is
 * rolled back. Keycloak failures need real commits: UserStatusFailureTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class UserStatusControllerTest {

	// Rows are inserted with this updated_at, so a test can tell whether the change moved it
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

	private UUID admin;
	private UUID alice;
	private UUID nurse;

	@BeforeEach
	void insertUsers() {
		admin = insertUser("admin", "ACTIVE");
		alice = insertUser("alice", "ACTIVE");
		nurse = jdbc.queryForObject("INSERT INTO roles (name, display_name) VALUES ('NURSE', 'Nurse') RETURNING id",
				UUID.class);
		jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", alice, nurse);
	}

	@Test
	void deactivateClosesTheKeycloakAccountAndKeepsTheRoles() throws Exception {
		patchStatus(alice, "INACTIVE")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("INACTIVE"))
				.andExpect(jsonPath("$.data.roles[0].name").value("NURSE"));

		verify(keycloakClient).setEnabled("kc-alice", false);
		verifyNoMoreInteractions(keycloakClient);
		entityManager.flush();
		assertThat(statusOf(alice)).isEqualTo("INACTIVE");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id = ?", Integer.class, alice))
				.isEqualTo(1);
		AuditAssert.exactlyOneRow(jdbc, AuditAction.USER_DEACTIVATED, alice.toString());
		Map<String, Object> audit = jdbc.queryForMap("SELECT before::text AS before, after::text AS after"
				+ " FROM audit_log WHERE action = 'USER_DEACTIVATED'");
		JSONAssert.assertEquals("{\"status\":\"ACTIVE\"}", (String) audit.get("before"), true);
		JSONAssert.assertEquals("{\"status\":\"INACTIVE\"}", (String) audit.get("after"), true);
	}

	@Test
	void deactivatedUsersNextRequestIsForbidden() throws Exception {
		patchStatus(alice, "INACTIVE").andExpect(status().isOk());

		// Alice's token is still valid; our service checks her status on every request
		mockMvc.perform(get("/admin/v1/users").header("Authorization", "Bearer " + tokens.forUser("kc-alice")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
				.andExpect(jsonPath("$.error.message")
						.value("Your account is not active. Ask an administrator to activate it."));
	}

	@Test
	void activateOpensTheKeycloakAccountWithTheSameRoles() throws Exception {
		UUID bob = insertUser("bob", "INACTIVE");
		jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", bob, nurse);

		patchStatus(bob, "ACTIVE")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("ACTIVE"))
				.andExpect(jsonPath("$.data.roles[0].name").value("NURSE"));

		verify(keycloakClient).setEnabled("kc-bob", true);
		entityManager.flush();
		AuditAssert.exactlyOneRow(jdbc, AuditAction.USER_ACTIVATED, bob.toString());
	}

	@Test
	void lockClosesTheKeycloakAccount() throws Exception {
		patchStatus(alice, "LOCKED")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("LOCKED"));

		verify(keycloakClient).setEnabled("kc-alice", false);
		entityManager.flush();
		AuditAssert.exactlyOneRow(jdbc, AuditAction.USER_LOCKED, alice.toString());
	}

	@Test
	void inactiveToLockedChangesOnlyTheLabel() throws Exception {
		UUID bob = insertUser("bob", "INACTIVE");

		patchStatus(bob, "LOCKED").andExpect(status().isOk());

		// The account is closed either way, so Keycloak is not asked
		verifyNoInteractions(keycloakClient);
		entityManager.flush();
		assertThat(statusOf(bob)).isEqualTo("LOCKED");
		AuditAssert.exactlyOneRow(jdbc, AuditAction.USER_LOCKED, bob.toString());
	}

	@Test
	void sameStatusWritesNothing() throws Exception {
		patchStatus(alice, "ACTIVE")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("ACTIVE"));

		verifyNoInteractions(keycloakClient);
		entityManager.flush();
		assertThat(jdbc.queryForObject("SELECT updated_at FROM users WHERE id = ?", Timestamp.class, alice).toInstant())
				.isEqualTo(OLD);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class)).isZero();
	}

	@Test
	void ownStatusIsRefused() throws Exception {
		expectRefused(admin, "INACTIVE", 400, "VALIDATION_ERROR",
				"You can't change your own status. Ask another administrator.");
	}

	@Test
	void unknownUserIsNotFound() throws Exception {
		UUID unknown = UUID.randomUUID();

		expectRefused(unknown, "INACTIVE", 404, "USER_NOT_FOUND", "No user with id " + unknown + ".");
	}

	@Test
	void unknownStatusIsRefused() throws Exception {
		expectRefused(alice, "FOO", 400, "VALIDATION_ERROR",
				"Request body is not valid JSON, or a field has the wrong type.");
	}

	@Test
	void missingStatusIsRefused() throws Exception {
		mockMvc.perform(patch("/admin/v1/users/" + alice + "/status")
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.message").value("Invalid request: status must not be null."));
		verifyNoInteractions(keycloakClient);
	}

	/** The given error, Keycloak never called, statuses as they were and no audit row. */
	private void expectRefused(UUID id, String status, int httpStatus, String code, String message) throws Exception {
		patchStatus(id, status)
				.andExpect(status().is(httpStatus))
				.andExpect(jsonPath("$.error.code").value(code))
				.andExpect(jsonPath("$.error.message").value(message));

		verifyNoInteractions(keycloakClient);
		entityManager.flush();
		assertThat(statusOf(admin)).isEqualTo("ACTIVE");
		assertThat(statusOf(alice)).isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class)).isZero();
	}

	private ResultActions patchStatus(UUID id, String status) throws Exception {
		return mockMvc.perform(patch("/admin/v1/users/" + id + "/status")
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"" + status + "\"}"));
	}

	private UUID insertUser(String username, String status) {
		return jdbc.queryForObject("INSERT INTO users (keycloak_id, username, email, first_name, last_name, status,"
				+ " updated_at) VALUES (?, ?, ?, 'A', 'B', ?, ?) RETURNING id", UUID.class, "kc-" + username, username,
				username + "@example.org", status, Timestamp.from(OLD));
	}

	private String statusOf(UUID id) {
		return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id);
	}

	private int auditRows(String action) {
		return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Integer.class, action);
	}
}
