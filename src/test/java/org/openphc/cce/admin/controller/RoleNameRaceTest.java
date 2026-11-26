package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
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
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.security.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Two creates of the same role name at the same moment: one wins, the other gets 409, never 500, and
 * only the winner syncs. A real server and real commits, so each test cleans up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RoleNameRaceTest {

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

	// The real repository, so a test can make the name check miss
	@MockitoSpyBean
	private RoleRepository roleRepository;

	@MockitoBean
	private GatewaySyncClient gatewaySyncClient;

	@Autowired
	private JdbcTemplate jdbc;

	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void insertAdmin() {
		jdbc.update("INSERT INTO users (keycloak_id, username, email, status) VALUES (?, ?, ?, ?)",
				"kc-admin", "admin", "admin@x.com", "ACTIVE");
	}

	@AfterEach
	void removeTestRows() {
		jdbc.update("DELETE FROM audit_log");
		jdbc.update("DELETE FROM api_permissions WHERE permission_name = 'FACILITY_ADMIN'");
		jdbc.update("DELETE FROM roles WHERE name = 'FACILITY_ADMIN'");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void constraintAloneGives409() throws Exception {
		// A role with no permission rows, so only the name check could stop the create; make it miss
		jdbc.update("INSERT INTO roles (name, display_name) VALUES ('FACILITY_ADMIN', 'Facility admin')");
		doReturn(false).when(roleRepository).existsByName("FACILITY_ADMIN");

		HttpResponse<String> response = postRole();

		assertThat(response.statusCode()).isEqualTo(409);
		assertThat(response.body()).isEqualTo("{\"error\":{\"code\":\"DUPLICATE_ROLE_NAME\","
				+ "\"message\":\"A role named 'FACILITY_ADMIN' already exists.\"}}");
		assertThat(count("SELECT count(*) FROM roles WHERE name = 'FACILITY_ADMIN'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM api_permissions WHERE permission_name = 'FACILITY_ADMIN'")).isZero();
		assertThat(count("SELECT count(*) FROM audit_log")).isZero();
		verifyNoInteractions(gatewaySyncClient);
	}

	@Test
	void tenParallelCreatesGiveOne201() throws Exception {
		CountDownLatch startGate = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < REQUESTS; i++) {
			results.add(pool.submit(() -> {
				startGate.await();
				return postRole().statusCode();
			}));
		}

		// Every thread is waiting at the gate; open it so all requests start together
		startGate.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get());
		}
		pool.shutdown();

		Collections.sort(statuses);
		List<Integer> expected = new ArrayList<>();
		expected.add(201);
		for (int i = 1; i < REQUESTS; i++) {
			expected.add(409);
		}
		assertThat(statuses).isEqualTo(expected);
		assertThat(count("SELECT count(*) FROM roles WHERE name = 'FACILITY_ADMIN'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM api_permissions WHERE permission_name = 'FACILITY_ADMIN'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'ROLE_CREATED'")).isEqualTo(1);
		verify(gatewaySyncClient, times(1)).sync(any(UUID.class));
	}

	private HttpResponse<String> postRole() throws Exception {
		String body = "{\"name\":\"FACILITY_ADMIN\",\"displayName\":\"Facility admin\"}";
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/v1/roles"))
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}
}
