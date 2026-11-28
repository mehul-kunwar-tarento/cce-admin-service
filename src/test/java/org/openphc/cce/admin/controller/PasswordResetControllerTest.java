package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditAssert;
import org.openphc.cce.admin.client.GatewaySyncClient;
import org.openphc.cce.admin.client.KeycloakClient;
import org.openphc.cce.admin.client.KeycloakException;
import org.openphc.cce.admin.security.TestTokens;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * POST /admin/v1/users/{id}/reset-password called the way a client calls it. Nothing in our tables changes
 * before Keycloak is asked, so a rolled-back test transaction shows every case.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetControllerTest {

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

	private UUID admin;
	private UUID alice;

	@BeforeEach
	void insertUsers() {
		admin = insertUser("admin", "ACTIVE");
		alice = insertUser("alice", "ACTIVE");
	}

	@Test
	void activeUserGetsTheEmail() throws Exception {
		reset(alice)
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(keycloakClient).sendUpdatePasswordEmail("kc-alice");
		verifyNoMoreInteractions(keycloakClient);
		entityManager.flush();
		AuditAssert.exactlyOneRow(jdbc, AuditAction.USER_PASSWORD_RESET, alice.toString());
		String after = jdbc.queryForObject("SELECT after::text FROM audit_log WHERE action = 'USER_PASSWORD_RESET'",
				String.class);
		JSONAssert.assertEquals("{\"email\":\"alice@example.org\",\"actions\":[\"UPDATE_PASSWORD\"]}", after, true);
		assertThat(jdbc.queryForObject("SELECT before IS NULL FROM audit_log WHERE action = 'USER_PASSWORD_RESET'",
				Boolean.class)).isTrue();
		// No password anywhere: the row only names the action
		assertThat(after.toLowerCase()).doesNotContain("\"password\"");
	}

	@Test
	void ownPasswordCanBeReset() throws Exception {
		reset(admin).andExpect(status().isNoContent());

		verify(keycloakClient).sendUpdatePasswordEmail("kc-admin");
	}

	@Test
	void inactiveUserIsRefused() throws Exception {
		UUID bob = insertUser("bob", "INACTIVE");

		expectRefused(bob, 400, "VALIDATION_ERROR", "Activate the user first.");
	}

	@Test
	void lockedUserIsRefused() throws Exception {
		UUID carol = insertUser("carol", "LOCKED");

		expectRefused(carol, 400, "VALIDATION_ERROR", "Activate the user first.");
	}

	@Test
	void unknownUserIsNotFound() throws Exception {
		UUID unknown = UUID.randomUUID();

		expectRefused(unknown, 404, "USER_NOT_FOUND", "No user with id " + unknown + ".");
	}

	@Test
	void keycloakFailureSendsNothingAndWritesNoAudit(CapturedOutput output) throws Exception {
		doThrow(new KeycloakException(
				"Keycloak at http://test-keycloak: could not send the update-password email to user kc-alice: ",
				"Failed to send execute actions email: Invalid sender address 'null'.", null))
				.when(keycloakClient).sendUpdatePasswordEmail(anyString());

		reset(alice)
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"))
				.andExpect(jsonPath("$.error.message").value("Password reset email not sent. Keycloak error: Failed to"
						+ " send execute actions email: Invalid sender address 'null'."));

		verify(keycloakClient).sendUpdatePasswordEmail("kc-alice");
		verifyNoMoreInteractions(keycloakClient);
		entityManager.flush();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class)).isZero();
		assertThat(output).contains("WARN").contains("Password reset email for user alice not sent: ");
	}

	/** The given error, Keycloak never called and no audit row. */
	private void expectRefused(UUID id, int httpStatus, String code, String message) throws Exception {
		reset(id)
				.andExpect(status().is(httpStatus))
				.andExpect(jsonPath("$.error.code").value(code))
				.andExpect(jsonPath("$.error.message").value(message));

		verifyNoInteractions(keycloakClient);
		entityManager.flush();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class)).isZero();
	}

	private ResultActions reset(UUID id) throws Exception {
		return mockMvc.perform(post("/admin/v1/users/" + id + "/reset-password")
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin")));
	}

	private UUID insertUser(String username, String status) {
		return jdbc.queryForObject("INSERT INTO users (keycloak_id, username, email, first_name, last_name, status)"
				+ " VALUES (?, ?, ?, 'A', 'B', ?) RETURNING id", UUID.class, "kc-" + username, username,
				username + "@example.org", status);
	}
}
