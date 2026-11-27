package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.client.KeycloakConflictException;
import org.openphc.cce.admin.client.KeycloakException;
import org.openphc.cce.admin.client.KeycloakRejectedException;
import org.openphc.cce.admin.repository.AuditLogRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * PUT /admin/v1/users/{id} when Keycloak refuses or fails, with real commits: only then can the test see that
 * the row written before the Keycloak call was really rolled back. Each test cleans up after itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class UserUpdateFailureTest {

	private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
	private static final String PREFIX = "Keycloak at http://test-keycloak: could not update user kc-alice: ";

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

	// The real repository, made to miss the email check in the race test
	@MockitoSpyBean
	private UserRepository userRepository;

	// The real repository, made to fail the USER_UPDATED row after Keycloak has taken the change
	@MockitoSpyBean
	private AuditLogRepository auditLogRepository;

	private UUID alice;

	@BeforeEach
	void insertUsers() {
		insertUser("admin", "admin@x.com", "ACTIVE", null);
		alice = insertUser("alice", "alice@example.org", "ACTIVE", "KE-001");
	}

	@AfterEach
	void removeRows() {
		jdbc.update("DELETE FROM users");
		jdbc.update("DELETE FROM audit_log");
	}

	@Test
	void keycloakHasTheEmailForAnUnknownAccount() throws Exception {
		keycloakFails(new KeycloakConflictException(PREFIX, "User exists with same email"));

		putNewEmail()
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("EMAIL_IN_USE"))
				.andExpect(jsonPath("$.error.message").value("Keycloak already has an account with this email that"
						+ " this service doesn't know. An operator must remove or link it. Nothing was changed."));

		expectNothingChanged();
	}

	@Test
	void keycloakRefusesAValue() throws Exception {
		keycloakFails(new KeycloakRejectedException(PREFIX, "firstName error-person-name-invalid-character"));

		putNewEmail()
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.message").value("Keycloak refused the change: firstName"
						+ " error-person-name-invalid-character. Nothing was changed."));

		expectNothingChanged();
	}

	@Test
	void keycloakDownChangesNothing(CapturedOutput output) throws Exception {
		keycloakFails(new KeycloakException(PREFIX, "Connection refused", null));

		putNewEmail()
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"))
				.andExpect(jsonPath("$.error.message")
						.value("Profile not changed. Keycloak error: Connection refused. Nothing was changed."));

		expectNothingChanged();
		assertThat(output).contains("WARN").contains("User alice not updated: " + PREFIX + "Connection refused");
	}

	@Test
	void emailTakenBetweenTheCheckAndTheWriteIsAConflict() throws Exception {
		insertUser("bob", "bob@example.org", "ACTIVE", null);
		// The check says the email is free, as it would if bob got it a moment after the check
		doReturn(false).when(userRepository)
				.existsByEmailInAnyCaseForAnotherUser("bob@example.org", alice);

		putUser(alice, body("bob@example.org", "Alice", "Otieno", "KE-001", null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("EMAIL_IN_USE"))
				.andExpect(jsonPath("$.error.message").value("Email bob@example.org is already used by another user."));

		verifyNoInteractions(keycloakClient);
		assertThat(row(alice)).containsEntry("email", "alice@example.org");
		assertThat(auditRows("USER_UPDATED")).isZero();
	}

	@Test
	void saveFailsAfterKeycloakChangedIsRecorded(CapturedOutput output) throws Exception {
		// Only the USER_UPDATED row fails; the failure record is written as normal
		doThrow(new IllegalStateException("database went away")).when(auditLogRepository)
				.save(argThat(row -> row != null && row.getAction() == AuditAction.USER_UPDATED));

		putNewEmail()
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"))
				.andExpect(jsonPath("$.error.message").value("Profile only partly applied: Keycloak has the new values,"
						+ " the user record does not. An operator must fix it."));

		// Keycloak changed once and is not changed back
		verify(keycloakClient).updateUser("kc-alice", "alice.o@example.org", "Alice", "Otieno");
		verifyNoMoreInteractions(keycloakClient);
		// Our row rolled back to the old values
		assertThat(row(alice)).containsEntry("email", "alice@example.org");
		assertThat(auditRows("USER_UPDATED")).isZero();

		Map<String, Object> failure = jdbc.queryForMap("SELECT entity_type, entity_id, after->>'keycloakId' AS keycloak_id,"
				+ " after->>'failedStep' AS failed_step, after->>'error' AS error, after->>'cleanup' AS cleanup"
				+ " FROM audit_log WHERE action = 'USER_UPDATE_FAILED'");
		assertThat(failure).containsEntry("entity_type", "USER").containsEntry("entity_id", alice.toString())
				.containsEntry("keycloak_id", "kc-alice").containsEntry("failed_step", "save user")
				.containsEntry("error", "database went away")
				.containsEntry("cleanup", "none — make Keycloak match the user record by hand");
		assertThat(output).contains("ERROR").contains("USER_UPDATE_FAILED USER " + alice + ": keycloakId=kc-alice");
	}

	private void keycloakFails(KeycloakException e) {
		doThrow(e).when(keycloakClient).updateUser(anyString(), anyString(), anyString(), anyString());
	}

	private ResultActions putNewEmail() throws Exception {
		return putUser(alice, body("alice.o@example.org", "Alice", "Otieno", "KE-001", null));
	}

	/** Keycloak was asked once; the committed row is as before and there is no audit row. */
	private void expectNothingChanged() {
		verify(keycloakClient).updateUser("kc-alice", "alice.o@example.org", "Alice", "Otieno");
		verifyNoMoreInteractions(keycloakClient);
		Map<String, Object> row = row(alice);
		assertThat(row).containsEntry("email", "alice@example.org");
		assertThat(((Timestamp) row.get("updated_at")).toInstant()).isEqualTo(OLD);
		assertThat(auditRows("USER_UPDATED")).isZero();
		assertThat(auditRows("USER_UPDATE_FAILED")).isZero();
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
