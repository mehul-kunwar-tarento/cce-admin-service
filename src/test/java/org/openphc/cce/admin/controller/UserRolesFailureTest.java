package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
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
 * PUT /admin/v1/users/{id}/roles when a Keycloak call or the save fails, with real commits: only then can the
 * test see that the rows were really rolled back and that a failure record survived. Each test cleans up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class UserRolesFailureTest {

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
	private JdbcTemplate jdbc;

	// No gateway or Keycloak in tests
	@MockitoBean
	private GatewaySyncClient gatewaySyncClient;

	@MockitoBean
	private KeycloakClient keycloakClient;

	@Autowired
	private MockMvc mockMvc;

	// The real repository, made to fail the roles audit row after Keycloak has taken the change
	@MockitoSpyBean
	private AuditLogRepository auditLogRepository;

	private UUID admin;
	private UUID alice;
	private UUID nurse;
	private UUID pharmacist;
	private UUID doctor;

	/** admin (the caller) and alice, who holds NURSE and PHARMACIST; DOCTOR exists too. */
	@BeforeEach
	void insertUsersAndRoles() {
		admin = insertUser("admin", "ACTIVE");
		alice = insertUser("alice", "ACTIVE");
		nurse = insertRole("NURSE");
		pharmacist = insertRole("PHARMACIST");
		doctor = insertRole("DOCTOR");
		giveRole(alice, nurse);
		giveRole(alice, pharmacist);
	}

	@AfterEach
	void removeRows() {
		jdbc.update("DELETE FROM user_roles");
		jdbc.update("DELETE FROM users");
		jdbc.update("DELETE FROM roles WHERE name IN ('NURSE', 'PHARMACIST', 'DOCTOR')");
		jdbc.update("DELETE FROM audit_log");
	}

	@Test
	void firstCallFailsAndNothingChanges(CapturedOutput output) throws Exception {
		doThrow(failure("assign realm roles DOCTOR to user kc-alice", "Connection refused")).when(keycloakClient)
				.assignRealmRoles(anyString(), anyList());

		putRoles(alice, body(pharmacist, doctor))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"))
				.andExpect(jsonPath("$.error.message")
						.value("Roles not changed. Keycloak error: Connection refused. Nothing was changed."));

		verify(keycloakClient).assignRealmRoles("kc-alice", List.of("DOCTOR"));
		verifyNoMoreInteractions(keycloakClient);
		expectRolesUnchangedAndNoAudit();
		assertThat(auditRows("USER_ROLES_UPDATE_FAILED")).isZero();
		assertThat(output).contains("WARN").contains("Roles of user alice not changed: ");
	}

	@Test
	void removeFailsAfterAssignIsRecorded(CapturedOutput output) throws Exception {
		doThrow(failure("remove realm roles NURSE from user kc-alice", "HTTP 500")).when(keycloakClient)
				.removeRealmRoles(anyString(), anyList());

		putRoles(alice, body(pharmacist, doctor))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"))
				.andExpect(jsonPath("$.error.message").value("Role change only partly applied in Keycloak. Keycloak and"
						+ " the user record now differ; an operator must fix it."));

		// DOCTOR was given and is not taken back
		verify(keycloakClient).assignRealmRoles("kc-alice", List.of("DOCTOR"));
		verify(keycloakClient).removeRealmRoles("kc-alice", List.of("NURSE"));
		verifyNoMoreInteractions(keycloakClient);
		expectRolesUnchangedAndNoAudit();
		Map<String, Object> failure = failureRow();
		assertThat(failure).containsEntry("entity_id", alice.toString()).containsEntry("keycloak_id", "kc-alice")
				.containsEntry("failed_step", "remove realm roles")
				.containsEntry("error", "Keycloak at http://test-keycloak: could not remove realm roles NURSE from user"
						+ " kc-alice: HTTP 500")
				.containsEntry("cleanup", "none — make Keycloak match the user record by hand");
		assertThat(output).contains("ERROR").contains("USER_ROLES_UPDATE_FAILED USER " + alice);
	}

	@Test
	void removeOnlyFailingIsAPlainFailure() throws Exception {
		doThrow(failure("remove realm roles PHARMACIST from user kc-alice", "HTTP 500")).when(keycloakClient)
				.removeRealmRoles(anyString(), anyList());

		// Nothing to add, so remove is the first call: nothing half done
		putRoles(alice, body(nurse))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.message").value("Roles not changed. Keycloak error: HTTP 500. Nothing was changed."));

		expectRolesUnchangedAndNoAudit();
		assertThat(auditRows("USER_ROLES_UPDATE_FAILED")).isZero();
	}

	@Test
	void saveFailsAfterKeycloakChangedIsRecorded() throws Exception {
		doThrow(new IllegalStateException("database went away")).when(auditLogRepository)
				.save(argThat(row -> row != null && row.getAction() == AuditAction.USER_ROLES_UPDATED));

		putRoles(alice, body(pharmacist, doctor))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.message").value("Role change only partly applied in Keycloak. Keycloak and"
						+ " the user record now differ; an operator must fix it."));

		expectRolesUnchangedAndNoAudit();
		assertThat(failureRow()).containsEntry("failed_step", "save user")
				.containsEntry("error", "database went away");
	}

	private KeycloakException failure(String step, String reason) {
		return new KeycloakException("Keycloak at http://test-keycloak: could not " + step + ": ", reason, null);
	}

	private void expectRolesUnchangedAndNoAudit() {
		assertThat(roleNamesOf(alice)).containsExactly("NURSE", "PHARMACIST");
		assertThat(auditRows("USER_ROLES_UPDATED")).isZero();
	}

	private Map<String, Object> failureRow() {
		return jdbc.queryForMap("SELECT entity_id, after->>'keycloakId' AS keycloak_id, after->>'failedStep' AS failed_step,"
				+ " after->>'error' AS error, after->>'cleanup' AS cleanup FROM audit_log"
				+ " WHERE action = 'USER_ROLES_UPDATE_FAILED'");
	}

	private ResultActions putRoles(UUID id, String body) throws Exception {
		return mockMvc.perform(put("/admin/v1/users/" + id + "/roles")
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private String body(UUID... roleIds) {
		List<String> quoted = new ArrayList<>();
		for (UUID id : roleIds) {
			quoted.add("\"" + id + "\"");
		}
		return "{\"roleIds\":[" + String.join(",", quoted) + "]}";
	}

	private UUID insertUser(String username, String status) {
		return jdbc.queryForObject("INSERT INTO users (keycloak_id, username, email, first_name, last_name, status)"
				+ " VALUES (?, ?, ?, 'A', 'B', ?) RETURNING id", UUID.class, "kc-" + username, username,
				username + "@example.org", status);
	}

	private UUID insertRole(String name) {
		return jdbc.queryForObject("INSERT INTO roles (name, display_name) VALUES (?, ?) RETURNING id", UUID.class,
				name, name);
	}

	private void giveRole(UUID userId, UUID roleId) {
		jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", userId, roleId);
	}

	private List<String> roleNamesOf(UUID userId) {
		return jdbc.queryForList("SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
				+ " WHERE ur.user_id = ? ORDER BY r.name", String.class, userId);
	}

	private int auditRows(String action) {
		return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Integer.class, action);
	}
}
