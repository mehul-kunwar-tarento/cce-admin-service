package org.openphc.cce.admin.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditAssert;
import org.openphc.cce.admin.client.KeycloakClient;
import org.openphc.cce.admin.client.KeycloakException;
import org.openphc.cce.admin.client.KeycloakUser;
import org.openphc.cce.admin.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Linking the first admin on a real Postgres, with Keycloak as a stand-in. Each test builds its own runner
 * with the username it needs and runs it; the app's own runner does nothing here (no username set).
 */
// The whole app starts (security included), so the JWT, Keycloak and gateway settings need values
@SpringBootTest(properties = { "JWT_ISSUER=http://test-keycloak/realms/cce", "JWT_AUDIENCE=cce-admin-service",
		"KEYCLOAK_BASE_URL=http://test-keycloak", "KEYCLOAK_CLIENT_SECRET=test-secret",
		"GATEWAY_BASE_URL=http://test-gateway" })
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class BootstrapAdminTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

	private static final KeycloakUser SUPERADMIN = new KeycloakUser("kc-123", "superadmin", "superadmin@cce.local",
			"Super", "Admin");

	@MockitoBean
	private KeycloakClient keycloakClient;

	// The real repository, so a test can make one of its methods fail
	@MockitoSpyBean
	private UserRepository userRepository;

	@Autowired
	private BootstrapAdminService service;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void emptyTablesAndStartWithoutRequest() {
		jdbc.update("DELETE FROM audit_log");
		jdbc.update("DELETE FROM user_roles");
		jdbc.update("DELETE FROM users");
		// The test framework puts an empty request on the thread; at a real startup there is none
		RequestContextHolder.resetRequestAttributes();
	}

	// ---- nothing to do ----

	@Test
	void unsetUsernameCallsNothing() {
		runWith("");

		verifyNoInteractions(keycloakClient);
		assertThat(count("users")).isZero();
	}

	@Test
	void alreadyLinkedSkipsKeycloak() {
		jdbc.update("INSERT INTO users (keycloak_id, username, email, status)"
				+ " VALUES ('kc-old', 'superadmin', 'old@cce.local', 'ACTIVE')");

		runWith("superadmin");

		verifyNoInteractions(keycloakClient);
		assertThat(count("users")).isEqualTo(1);
		assertThat(count("user_roles")).isZero();
		assertThat(count("audit_log")).isZero();
	}

	// ---- Keycloak says no: ERROR, nothing written, startup goes on ----

	@Test
	void unreachableKeycloakLogsAndKeepsRunning(CapturedOutput output) {
		when(keycloakClient.findUserByUsername("superadmin")).thenThrow(new KeycloakException(
				"Keycloak at http://test-keycloak: could not find user superadmin: Connection refused", null));

		assertThatCode(() -> runWith("superadmin")).doesNotThrowAnyException();

		assertThat(output).contains("ERROR");
		assertThat(output).contains("Bootstrap admin superadmin not linked: Keycloak at http://test-keycloak:"
				+ " could not find user superadmin: Connection refused; check that Keycloak is running,"
				+ " or create superadmin in Keycloak first, then restart");
		assertNothingWritten();
	}

	@Test
	void userNotFoundLogsAndWritesNothing(CapturedOutput output) {
		when(keycloakClient.findUserByUsername("superadmin")).thenReturn(Optional.empty());

		runWith("superadmin");

		assertThat(output).contains("Bootstrap admin superadmin not linked: no such user in Keycloak;"
				+ " create superadmin in Keycloak first, then restart");
		assertNothingWritten();
	}

	@Test
	void userWithoutSuperAdminRoleWritesNothing(CapturedOutput output) {
		when(keycloakClient.findUserByUsername("superadmin")).thenReturn(Optional.of(SUPERADMIN));
		when(keycloakClient.hasRealmRole("kc-123", "SUPER_ADMIN")).thenReturn(false);

		runWith("superadmin");

		assertThat(output).contains("Bootstrap admin superadmin not linked: the Keycloak user has no realm role"
				+ " SUPER_ADMIN; give it the role, then restart");
		assertNothingWritten();
	}

	@Test
	void userWithoutEmailWritesNothing(CapturedOutput output) {
		KeycloakUser noEmail = new KeycloakUser("kc-123", "superadmin", null, "Super", "Admin");
		when(keycloakClient.findUserByUsername("superadmin")).thenReturn(Optional.of(noEmail));
		when(keycloakClient.hasRealmRole("kc-123", "SUPER_ADMIN")).thenReturn(true);

		runWith("superadmin");

		assertThat(output).contains("Bootstrap admin superadmin not linked: superadmin has no email in Keycloak;"
				+ " add one, then restart");
		assertNothingWritten();
	}

	// ---- linked ----

	@Test
	void linkedUserGetsThreeRows(CapturedOutput output) {
		keycloakHasSuperadmin();

		runWith("superadmin");

		Map<String, Object> user = jdbc.queryForMap("SELECT * FROM users");
		assertThat(user.get("keycloak_id")).isEqualTo("kc-123");
		assertThat(user.get("username")).isEqualTo("superadmin");
		assertThat(user.get("email")).isEqualTo("superadmin@cce.local");
		assertThat(user.get("first_name")).isEqualTo("Super");
		assertThat(user.get("last_name")).isEqualTo("Admin");
		assertThat(user.get("status")).isEqualTo("ACTIVE");
		UUID userId = (UUID) user.get("id");

		String roleName = jdbc.queryForObject("SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
				+ " WHERE ur.user_id = ?", String.class, userId);
		assertThat(roleName).isEqualTo("SUPER_ADMIN");

		AuditAssert.exactlyOneRow(jdbc, AuditAction.BOOTSTRAP_ADMIN_LINKED, userId.toString());
		Map<String, Object> audit = jdbc.queryForMap("SELECT actor_id, actor_username, entity_type, before,"
				+ " after ->> 'keycloakId' AS keycloak_id, after ->> 'username' AS username FROM audit_log");
		assertThat(audit.get("actor_username")).isEqualTo("SYSTEM");
		assertThat(audit.get("actor_id")).isNull();
		assertThat(audit.get("entity_type")).isEqualTo("USER");
		assertThat(audit.get("before")).isNull();
		assertThat(audit.get("keycloak_id")).isEqualTo("kc-123");
		assertThat(audit.get("username")).isEqualTo("superadmin");

		assertThat(output).contains("INFO");
		assertThat(output).contains("bootstrap admin superadmin linked");
	}

	@Test
	void failedRoleInsertLeavesNoRows(CapturedOutput output) {
		keycloakHasSuperadmin();
		doThrow(new DataAccessResourceFailureException("insert failed")).when(userRepository).addRole(any(),
				any());

		assertThatCode(() -> runWith("superadmin")).doesNotThrowAnyException();

		assertThat(output).contains("Bootstrap admin superadmin not linked: insert failed");
		assertNothingWritten();
	}

	@Test
	void secondStartSkips() {
		keycloakHasSuperadmin();

		runWith("superadmin");
		runWith("superadmin");

		verify(keycloakClient, times(1)).findUserByUsername("superadmin");
		assertThat(count("users")).isEqualTo(1);
		assertThat(count("audit_log")).isEqualTo(1);
	}

	@Test
	void uppercaseUsernameLinksOnceAndSkipsAfter() {
		keycloakHasSuperadmin();

		runWith(" SuperAdmin ");
		runWith("SUPERADMIN");

		verify(keycloakClient, times(1)).findUserByUsername("superadmin");
		verify(keycloakClient, never()).findUserByUsername("SuperAdmin");
		assertThat(jdbc.queryForObject("SELECT username FROM users", String.class)).isEqualTo("superadmin");
		assertThat(count("users")).isEqualTo(1);
	}

	// ---- helpers ----

	private void runWith(String configuredUsername) {
		new BootstrapAdminRunner(keycloakClient, service, configuredUsername).run(null);
	}

	private void keycloakHasSuperadmin() {
		when(keycloakClient.findUserByUsername("superadmin")).thenReturn(Optional.of(SUPERADMIN));
		when(keycloakClient.hasRealmRole("kc-123", "SUPER_ADMIN")).thenReturn(true);
	}

	private void assertNothingWritten() {
		assertThat(count("users")).isZero();
		assertThat(count("user_roles")).isZero();
		assertThat(count("audit_log")).isZero();
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}
}
