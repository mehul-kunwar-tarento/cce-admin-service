package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.client.GatewaySyncClient;
import org.openphc.cce.admin.client.KeycloakClient;
import org.openphc.cce.admin.client.KeycloakException;
import org.openphc.cce.admin.repository.AuditLogRepository;
import org.openphc.cce.admin.security.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PATCH /admin/v1/users/{id}/status when Keycloak or the save fails, with real commits: only then can the test
 * see that the row was really rolled back and that a failure record survived. Each test cleans up after itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class UserStatusFailureTest {

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

	// The real repository, made to fail the status audit row after Keycloak has taken the change
	@MockitoSpyBean
	private AuditLogRepository auditLogRepository;

	private UUID alice;

	@BeforeEach
	void insertUsers() {
		insertUser("admin", "ACTIVE");
		alice = insertUser("alice", "ACTIVE");
	}

	@AfterEach
	void removeRows() {
		jdbc.update("DELETE FROM users");
		jdbc.update("DELETE FROM audit_log");
	}

	@Test
	void keycloakFailureChangesNothing(CapturedOutput output) throws Exception {
		doThrow(new KeycloakException("Keycloak at http://test-keycloak: could not set enabled=false for user kc-alice: ",
				"Connection refused", null)).when(keycloakClient).setEnabled(anyString(), anyBoolean());

		patchStatus(alice, "INACTIVE")
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"))
				.andExpect(jsonPath("$.error.message")
						.value("Status not changed. Keycloak error: Connection refused. Nothing was changed."));

		verify(keycloakClient).setEnabled("kc-alice", false);
		verifyNoMoreInteractions(keycloakClient);
		assertThat(statusOf(alice)).isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject("SELECT updated_at FROM users WHERE id = ?", Timestamp.class, alice).toInstant())
				.isEqualTo(OLD);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class)).isZero();
		assertThat(output).contains("WARN").contains("Status of user alice not changed: ");
	}

	@Test
	void saveFailsAfterKeycloakChangedIsRecorded(CapturedOutput output) throws Exception {
		// Only the status row fails; the failure record is written as normal
		doThrow(new IllegalStateException("database went away")).when(auditLogRepository)
				.save(argThat(row -> row != null && row.getAction() == AuditAction.USER_DEACTIVATED));

		patchStatus(alice, "INACTIVE")
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"))
				.andExpect(jsonPath("$.error.message").value("Status change only partly applied: Keycloak account"
						+ " disabled, user record still ACTIVE. Keycloak and the user record now differ; an operator"
						+ " must fix it."));

		// Keycloak closed once and is not opened again
		verify(keycloakClient).setEnabled("kc-alice", false);
		verifyNoMoreInteractions(keycloakClient);
		assertThat(statusOf(alice)).isEqualTo("ACTIVE");
		assertThat(auditRows("USER_DEACTIVATED")).isZero();
		Map<String, Object> failure = jdbc.queryForMap("SELECT entity_id, after->>'keycloakId' AS keycloak_id,"
				+ " after->>'failedStep' AS failed_step, after->>'cleanup' AS cleanup"
				+ " FROM audit_log WHERE action = 'USER_STATUS_UPDATE_FAILED'");
		assertThat(failure).containsEntry("entity_id", alice.toString()).containsEntry("keycloak_id", "kc-alice")
				.containsEntry("failed_step", "save user")
				.containsEntry("cleanup", "none — make Keycloak match the user record by hand");
		assertThat(output).contains("ERROR").contains("USER_STATUS_UPDATE_FAILED USER " + alice);
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
