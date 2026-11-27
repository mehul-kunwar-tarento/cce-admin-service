package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openphc.cce.admin.client.GatewaySyncClient;
import org.openphc.cce.admin.client.KeycloakClient;
import org.openphc.cce.admin.client.KeycloakConflictException;
import org.openphc.cce.admin.client.KeycloakException;
import org.openphc.cce.admin.client.KeycloakRejectedException;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.openphc.cce.admin.security.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
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
 * POST /users when Keycloak or the save fails, with real commits: a failure record is written in its own
 * transaction, which a rolled-back test transaction could not show. Each test cleans up after itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class UserCreateFailureTest {

	private static final String KEYCLOAK_ID = "9ed584e3-2781-4abe-909e-a4a8a30a0f3e";
	private static final String PREFIX = "Keycloak at http://test-keycloak: could not create user alice: ";

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

	// The real repository, made to fail on purpose in the "save fails" test
	@MockitoSpyBean
	private UserRoleRepository userRoleRepository;

	private UUID nurse;

	@BeforeEach
	void insertAdminAndRole() {
		jdbc.update("INSERT INTO users (keycloak_id, username, email, status) VALUES (?, ?, ?, ?)",
				"kc-admin", "admin", "admin@x.com", "ACTIVE");
		nurse = jdbc.queryForObject("INSERT INTO roles (name, display_name) VALUES ('NURSE', 'Nurse') RETURNING id",
				UUID.class);
	}

	@AfterEach
	void removeRows() {
		jdbc.update("DELETE FROM user_roles");
		jdbc.update("DELETE FROM users");
		jdbc.update("DELETE FROM roles WHERE name = 'NURSE'");
		jdbc.update("DELETE FROM audit_log");
	}

	// ---- Keycloak create fails: nothing exists, nothing to undo or record ----

	@Test
	void keycloakAlreadyHasTheAccount() throws Exception {
		when(keycloakClient.createUser(anyString(), anyString(), anyString(), anyString()))
				.thenThrow(new KeycloakConflictException(PREFIX, "User exists with same username"));

		postAlice()
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("EMAIL_IN_USE"))
				.andExpect(jsonPath("$.error.message").value("Keycloak already has an account with this username or"
						+ " email that this service doesn't know. An operator must remove or link it."));

		expectOnlyCreateCalled();
		expectNothingSaved();
	}

	@Test
	void keycloakRefusesAValue() throws Exception {
		when(keycloakClient.createUser(anyString(), anyString(), anyString(), anyString()))
				.thenThrow(new KeycloakRejectedException(PREFIX, "username error-invalid-length"));

		postAlice()
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.message")
						.value("Keycloak refused the user: username error-invalid-length. Nothing was created."));

		expectOnlyCreateCalled();
		expectNothingSaved();
	}

	@Test
	void keycloakDownCreatesNothing(CapturedOutput output) throws Exception {
		when(keycloakClient.createUser(anyString(), anyString(), anyString(), anyString()))
				.thenThrow(new KeycloakException(PREFIX, "Connection refused", null));

		postAlice()
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"))
				.andExpect(jsonPath("$.error.message").value("User was not created. Keycloak could not create the"
						+ " account: Connection refused. Nothing was changed. Try again."));

		expectOnlyCreateCalled();
		expectNothingSaved();
		assertThat(output).contains("WARN").contains("User alice not created: " + PREFIX + "Connection refused");
	}

	// ---- a step after the account was made fails: undo once, record ----

	@Test
	void assignFailsAndTheAccountIsRemoved(CapturedOutput output) throws Exception {
		keycloakCreatesTheAccount();
		doThrow(new KeycloakException("Keycloak at http://test-keycloak: could not assign realm roles NURSE to user "
				+ KEYCLOAK_ID + ": ", "Keycloak has no realm role NURSE", null))
				.when(keycloakClient).assignRealmRoles(KEYCLOAK_ID, List.of("NURSE"));

		postAlice()
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"))
				.andExpect(jsonPath("$.error.message").value("User was not created. The Keycloak account was created"
						+ " but assigning its realm roles failed: Keycloak has no realm role NURSE; it has been removed"
						+ " again. Nothing was changed."));

		expectCreateAssignDeleteOnce();
		expectNothingSaved();
		Map<String, Object> failure = failureRow();
		assertThat(failure).containsEntry("entity_type", "USER").containsEntry("entity_id", "alice")
				.containsEntry("keycloak_id", KEYCLOAK_ID).containsEntry("failed_step", "assign realm roles")
				.containsEntry("cleanup", "keycloak user deleted");
		assertThat((String) failure.get("error")).endsWith("Keycloak has no realm role NURSE");
		assertThat(output).contains("ERROR").contains("USER_CREATE_FAILED USER alice: keycloakId=" + KEYCLOAK_ID);
	}

	@Test
	void saveFailsAndTheAccountIsRemoved() throws Exception {
		keycloakCreatesTheAccount();
		doThrow(new DataIntegrityViolationException("database said no")).when(userRoleRepository).flush();

		postAlice()
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"))
				.andExpect(jsonPath("$.error.message").value("User was not created. The Keycloak account was created"
						+ " but saving the user failed; it has been removed again. Nothing was changed. Try again."));

		expectCreateAssignDeleteOnce();
		// The user row was inserted before the failure; the rollback took it away again
		expectNothingSaved();
		assertThat(failureRow()).containsEntry("entity_id", "alice").containsEntry("failed_step", "save user")
				.containsEntry("error", "database said no").containsEntry("cleanup", "keycloak user deleted");
	}

	@Test
	void undoFailsAndAnOperatorMustDeleteTheAccount() throws Exception {
		keycloakCreatesTheAccount();
		doThrow(new KeycloakException("Keycloak at http://test-keycloak: could not assign realm roles NURSE to user "
				+ KEYCLOAK_ID + ": ", "HTTP 500", null))
				.when(keycloakClient).assignRealmRoles(KEYCLOAK_ID, List.of("NURSE"));
		doThrow(new KeycloakException("Keycloak at http://test-keycloak: could not delete user " + KEYCLOAK_ID + ": ",
				"Connection refused", null))
				.when(keycloakClient).deleteUser(KEYCLOAK_ID);

		postAlice()
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"))
				.andExpect(jsonPath("$.error.message").value("User was not created. The Keycloak account " + KEYCLOAK_ID
						+ " was created but could not be removed. An operator must delete it in Keycloak before"
						+ " retrying with the same username/email."));

		expectCreateAssignDeleteOnce();
		expectNothingSaved();
		assertThat(failureRow()).containsEntry("failed_step", "assign realm roles")
				.containsEntry("cleanup", "FAILED — delete keycloak user " + KEYCLOAK_ID + " by hand");
	}

	private void keycloakCreatesTheAccount() {
		when(keycloakClient.createUser(anyString(), anyString(), anyString(), anyString())).thenReturn(KEYCLOAK_ID);
	}

	/** Keycloak create was tried once; nothing else was asked of Keycloak. */
	private void expectOnlyCreateCalled() {
		verify(keycloakClient).createUser("alice", "alice@example.org", "Alice", "Otieno");
		verifyNoMoreInteractions(keycloakClient);
	}

	/** Create, assign and the one undo, each once; nothing retried. */
	private void expectCreateAssignDeleteOnce() {
		verify(keycloakClient).createUser("alice", "alice@example.org", "Alice", "Otieno");
		verify(keycloakClient).assignRealmRoles(KEYCLOAK_ID, List.of("NURSE"));
		verify(keycloakClient).deleteUser(KEYCLOAK_ID);
		verifyNoMoreInteractions(keycloakClient);
	}

	/** No alice in users or user_roles, and no USER_CREATED row; only the admin from the set-up is left. */
	private void expectNothingSaved() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = 'alice'", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'USER_CREATED'", Integer.class))
				.isZero();
	}

	/** The one USER_CREATE_FAILED row, with the fields of its after JSON as columns. */
	private Map<String, Object> failureRow() {
		List<Map<String, Object>> rows = jdbc.queryForList("SELECT entity_type, entity_id,"
				+ " after->>'keycloakId' AS keycloak_id, after->>'failedStep' AS failed_step,"
				+ " after->>'error' AS error, after->>'cleanup' AS cleanup"
				+ " FROM audit_log WHERE action = 'USER_CREATE_FAILED'");
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	private ResultActions postAlice() throws Exception {
		return mockMvc.perform(post("/admin/v1/users")
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"alice\",\"email\":\"alice@example.org\",\"firstName\":\"Alice\","
						+ "\"lastName\":\"Otieno\",\"roleIds\":[\"" + nurse + "\"]}"));
	}
}
