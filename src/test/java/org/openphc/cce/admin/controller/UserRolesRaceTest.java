package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.client.GatewaySyncClient;
import org.openphc.cce.admin.client.KeycloakClient;
import org.openphc.cce.admin.security.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The same role change sent many times at once, as a double click or two admins do: every request answers 200
 * and the change is made once. A real server and real commits (two requests can't share one test
 * transaction), so each test cleans up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UserRolesRaceTest {

	private static final int REQUESTS = 10;

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

	@LocalServerPort
	private int port;

	private final HttpClient http = HttpClient.newHttpClient();

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
	void tenIdenticalChangesAtOnceGiveOneChange() throws Exception {
		String body = body(pharmacist, doctor);
		CountDownLatch startGate = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < REQUESTS; i++) {
			results.add(pool.submit(() -> {
				startGate.await();
				return putRolesOverHttp(body).statusCode();
			}));
		}

		// Every thread is waiting at the gate; open it so all requests start together
		startGate.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get());
		}
		pool.shutdown();

		List<Integer> expected = new ArrayList<>();
		for (int i = 0; i < REQUESTS; i++) {
			expected.add(200);
		}
		assertThat(statuses).isEqualTo(expected);
		assertThat(roleNamesOf(alice)).containsExactly("DOCTOR", "PHARMACIST");
		assertThat(auditRows("USER_ROLES_UPDATED")).isEqualTo(1);
		// Keycloak was asked once: the others waited at the lock and then found nothing to change
		verify(keycloakClient).assignRealmRoles("kc-alice", List.of("DOCTOR"));
		verify(keycloakClient).removeRealmRoles("kc-alice", List.of("NURSE"));
		verifyNoMoreInteractions(keycloakClient);
	}

	private HttpResponse<String> putRolesOverHttp(String body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(
				URI.create("http://localhost:" + port + "/admin/v1/users/" + alice + "/roles"))
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin"))
				.header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString(body))
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
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
