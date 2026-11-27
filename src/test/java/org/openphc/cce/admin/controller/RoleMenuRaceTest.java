package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.openphc.cce.admin.security.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The same save of a role's menus sent many times at once, as a double click does: every request answers
 * 200 and the change is made once. A real server and real commits (two requests can't share one test
 * transaction), so each test cleans up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RoleMenuRaceTest {

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

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbc;

	private final HttpClient http = HttpClient.newHttpClient();

	private UUID roleId;

	@BeforeEach
	void insertAdminAndRole() {
		jdbc.update("INSERT INTO users (keycloak_id, username, email, status) VALUES (?, ?, ?, ?)",
				"kc-admin", "admin", "admin@x.com", "ACTIVE");
		roleId = jdbc.queryForObject("INSERT INTO roles (name, display_name) VALUES ('NURSE', 'Nurse') RETURNING id",
				UUID.class);
	}

	@AfterEach
	void removeTestRows() {
		jdbc.update("DELETE FROM audit_log");
		jdbc.update("DELETE FROM role_menus WHERE role_id = ?", roleId);
		jdbc.update("DELETE FROM roles WHERE id = ?", roleId);
		jdbc.update("DELETE FROM users");
	}

	@Test
	void tenIdenticalSavesAtOnceGiveOneChange() throws Exception {
		String body = "{\"menuIds\":[\"" + menuId("dashboard") + "\",\"" + menuId("compliance") + "\"]}";
		CountDownLatch startGate = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < REQUESTS; i++) {
			results.add(pool.submit(() -> {
				startGate.await();
				return putMenus(body).statusCode();
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
		assertThat(count("SELECT count(*) FROM role_menus WHERE role_id = '" + roleId + "'")).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'ROLE_MENUS_UPDATED'")).isEqualTo(1);
	}

	private HttpResponse<String> putMenus(String body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(
				URI.create("http://localhost:" + port + "/admin/v1/roles/" + roleId + "/menus"))
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin"))
				.header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString(body))
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private UUID menuId(String key) {
		return jdbc.queryForObject("SELECT id FROM menus WHERE key = ?", UUID.class, key);
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}
}
