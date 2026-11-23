package org.openphc.cce.admin.security;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The local-user check on a real users table: a genuine token gets through only if its sub belongs to
 * an ACTIVE user. Users are inserted with SQL and removed after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ActiveUserCheckTest {

	private static final String NOT_SET_UP_BODY = "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":"
			+ "\"Your account is not set up in the admin service. Ask an administrator to add you.\"}}";
	private static final String NOT_ACTIVE_BODY = "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":"
			+ "\"Your account is not active. Ask an administrator to activate it.\"}}";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

	static TestTokens tokens = new TestTokens();

	@DynamicPropertySource
	static void jwtSettings(DynamicPropertyRegistry registry) {
		registry.add("JWT_ISSUER", () -> TestTokens.ISSUER);
		registry.add("JWT_AUDIENCE", () -> TestTokens.AUDIENCE);
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

	@AfterEach
	void removeUsers() {
		jdbc.update("DELETE FROM users");
	}

	// ---- 403 ----

	@Test
	void unknownUserIs403NotSetUp() throws Exception {
		callAs(tokens.forUser("kc-nobody"), "/test/secure")
				.andExpect(status().isForbidden())
				.andExpect(content().json(NOT_SET_UP_BODY, JsonCompareMode.STRICT));
	}

	@Test
	void inactiveUserIs403NotActive() throws Exception {
		insertUser("kc-ravi", "ravi", "INACTIVE");

		callAs(tokens.forUser("kc-ravi"), "/test/secure")
				.andExpect(status().isForbidden())
				.andExpect(content().json(NOT_ACTIVE_BODY, JsonCompareMode.STRICT));
	}

	@Test
	void lockedUserIs403NotActive() throws Exception {
		insertUser("kc-ravi", "ravi", "LOCKED");

		callAs(tokens.forUser("kc-ravi"), "/test/secure")
				.andExpect(status().isForbidden())
				.andExpect(content().json(NOT_ACTIVE_BODY, JsonCompareMode.STRICT));
	}

	// ---- allowed ----

	@Test
	void activeUserReachesTheEndpoint() throws Exception {
		insertUser("kc-asha", "asha", "ACTIVE");

		callAs(tokens.forUser("kc-asha"), "/test/secure")
				.andExpect(status().isOk())
				.andExpect(content().string("ok"));
	}

	@Test
	void currentUserHasIdUsernameAndRealmRoles() throws Exception {
		UUID id = insertUser("kc-asha", "asha", "ACTIVE");
		Map<String, Object> realmAccess = Map.of("roles", List.of("SUPER_ADMIN", "default-roles-cce"));

		callAs(tokens.forUserWithClaim("kc-asha", "realm_access", realmAccess), "/test/current-user")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.username").value("asha"))
				.andExpect(jsonPath("$.roles").value(contains("SUPER_ADMIN", "default-roles-cce")));
	}

	@Test
	void tokenWithoutRolesIsNotRejected() throws Exception {
		insertUser("kc-asha", "asha", "ACTIVE");

		callAs(tokens.forUser("kc-asha"), "/test/current-user")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roles").isEmpty());
	}

	@Test
	void healthSkipsTheUserCheck() throws Exception {
		callAs(tokens.forUser("kc-nobody"), "/admin/v1/health")
				.andExpect(status().isOk());
	}

	// ---- helpers ----

	private ResultActions callAs(String token, String path) throws Exception {
		return mockMvc.perform(get(path).header("Authorization", "Bearer " + token));
	}

	private UUID insertUser(String keycloakId, String username, String status) {
		return jdbc.queryForObject(
				"INSERT INTO users (keycloak_id, username, email, status) VALUES (?, ?, ?, ?) RETURNING id",
				UUID.class, keycloakId, username, username + "@x.com", status);
	}
}
