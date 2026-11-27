package org.openphc.cce.admin.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
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

/**
 * GET /admin/v1/users and /users/{id} called the way a client calls them, on a fixed set of users.
 * Each test runs in a transaction that is rolled back, so tests never see each other's rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class UserQueryControllerTest {

	private static final Instant LOGIN = Instant.parse("2026-11-20T09:14:03Z");

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

	// No gateway or Keycloak in tests; reading users never calls either
	@MockitoBean
	private GatewaySyncClient gatewaySyncClient;

	@MockitoBean
	private KeycloakClient keycloakClient;

	private UUID nurse;
	private UUID pharmacist;
	private UUID alice;

	/**
	 * admin (the caller) and five users; each of alice, bob, carol and dave has "ali" in a different field:
	 * username, first name, last name, email.
	 */
	@BeforeEach
	void insertUsers() {
		insertUser("admin", "admin@x.com", "Ad", "Min", "ACTIVE", null);
		nurse = insertRole("NURSE", "Nurse");
		pharmacist = insertRole("PHARMACIST", "Pharmacist");

		alice = insertUser("alice", "alice@example.org", "Alice", "Otieno", "ACTIVE", "KE-001");
		jdbc.update("UPDATE users SET last_login_at = ? WHERE id = ?", Timestamp.from(LOGIN), alice);
		giveRole(alice, pharmacist);
		giveRole(alice, nurse);

		UUID bob = insertUser("bob", "bob@example.org", "Malia", "Mwangi", "INACTIVE", null);
		giveRole(bob, nurse);
		UUID carol = insertUser("carol", "carol@example.org", "Carol", "Kalimo", "LOCKED", null);
		giveRole(carol, nurse);
		insertUser("dave", "dave.ali@example.org", "Dave", "Njoroge", "ACTIVE", null);
		insertUser("eve", "eve@example.org", "Eve", "Wanjiru", "ACTIVE", "ke-001");
	}

	// ---- list ----

	@Test
	void pagesAreAToZWithTheTotalOfAllMatches() throws Exception {
		list("?page=1&pageSize=2")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].username").value(contains("admin", "alice")))
				.andExpect(jsonPath("$.pagination.page").value(1))
				.andExpect(jsonPath("$.pagination.pageSize").value(2))
				.andExpect(jsonPath("$.pagination.total").value(6));
		list("?page=2&pageSize=2")
				.andExpect(jsonPath("$.data[*].username").value(contains("bob", "carol")));
	}

	@Test
	void withoutStatusInactiveAndLockedUsersAreIncluded() throws Exception {
		list("")
				.andExpect(jsonPath("$.data[*].username").value(contains("admin", "alice", "bob", "carol", "dave", "eve")));
	}

	@Test
	void searchLooksInUsernameNamesAndEmailIgnoringCase() throws Exception {
		list("?q=ALI")
				.andExpect(jsonPath("$.data[*].username").value(contains("alice", "bob", "carol", "dave")))
				.andExpect(jsonPath("$.pagination.total").value(4));
	}

	@Test
	void blankSearchIsNoSearch() throws Exception {
		search("   ")
				.andExpect(jsonPath("$.pagination.total").value(6));
	}

	@Test
	void percentAndUnderscoreAreSearchedAsPlainCharacters() throws Exception {
		insertUser("promo", "promo@example.org", "Fifty", "50% Off", "ACTIVE", null);
		insertUser("under_score", "us@example.org", "Un", "Der", "ACTIVE", null);

		search("50%")
				.andExpect(jsonPath("$.data[*].username").value(contains("promo")));
		search("_")
				.andExpect(jsonPath("$.data[*].username").value(contains("under_score")));
	}

	@Test
	void filtersCombineWithAnd() throws Exception {
		list("?status=INACTIVE&roleId=" + nurse)
				.andExpect(jsonPath("$.data[*].username").value(contains("bob")));
		list("?q=ali&status=ACTIVE&roleId=" + nurse)
				.andExpect(jsonPath("$.data[*].username").value(contains("alice")));
	}

	@Test
	void unknownStatusIsRefused() throws Exception {
		list("?status=FOO")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.message").value("Invalid request: status has an invalid value."));
	}

	@Test
	void unknownRoleMatchesNobody() throws Exception {
		list("?roleId=" + UUID.randomUUID())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").value(empty()))
				.andExpect(jsonPath("$.pagination.total").value(0));
	}

	@Test
	void facilityScopeIsAnExactMatch() throws Exception {
		list("?facilityScope=KE-001")
				.andExpect(jsonPath("$.data[*].username").value(contains("alice")));
		list("?facilityScope=ke-001")
				.andExpect(jsonPath("$.data[*].username").value(contains("eve")));
	}

	@Test
	void eachUserHasEveryFieldAndRolesAToZ() throws Exception {
		list("?q=alice")
				.andExpect(jsonPath("$.data[0].id").value(alice.toString()))
				.andExpect(jsonPath("$.data[0].username").value("alice"))
				.andExpect(jsonPath("$.data[0].email").value("alice@example.org"))
				.andExpect(jsonPath("$.data[0].firstName").value("Alice"))
				.andExpect(jsonPath("$.data[0].lastName").value("Otieno"))
				.andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
				.andExpect(jsonPath("$.data[0].roles[*].name").value(contains("NURSE", "PHARMACIST")))
				.andExpect(jsonPath("$.data[0].roles[0].id").value(nurse.toString()))
				.andExpect(jsonPath("$.data[0].roles[0].displayName").value("Nurse"))
				.andExpect(jsonPath("$.data[0].facilityScope").value("KE-001"))
				.andExpect(jsonPath("$.data[0].lastLoginAt").value("2026-11-20T09:14:03Z"))
				.andExpect(jsonPath("$.data[0].createdAt").isNotEmpty());
	}

	@Test
	void userWithoutRolesHasAnEmptyList() throws Exception {
		list("?q=dave")
				.andExpect(jsonPath("$.data[0].roles").value(empty()))
				.andExpect(jsonPath("$.data[0].lastLoginAt").value(nullValue()));
	}

	// ---- one user ----

	@Test
	void oneUserHasTheSameShape() throws Exception {
		mockMvc.perform(get("/admin/v1/users/" + alice).header("Authorization", "Bearer " + tokens.forUser("kc-admin")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.username").value("alice"))
				.andExpect(jsonPath("$.data.roles[*].name").value(contains("NURSE", "PHARMACIST")))
				.andExpect(jsonPath("$.data.lastLoginAt").value("2026-11-20T09:14:03Z"));
	}

	@Test
	void unknownUserIsNotFound() throws Exception {
		UUID unknown = UUID.randomUUID();

		mockMvc.perform(get("/admin/v1/users/" + unknown).header("Authorization", "Bearer " + tokens.forUser("kc-admin")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value("No user with id " + unknown + "."));
	}

	private ResultActions list(String query) throws Exception {
		return mockMvc.perform(get("/admin/v1/users" + query)
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin")));
	}

	/** q passed as a parameter, so spaces and % reach the server exactly as written. */
	private ResultActions search(String q) throws Exception {
		return mockMvc.perform(get("/admin/v1/users").param("q", q)
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin")));
	}

	private UUID insertUser(String username, String email, String firstName, String lastName, String status,
			String facilityScope) {
		return jdbc.queryForObject("INSERT INTO users (keycloak_id, username, email, first_name, last_name, status,"
				+ " facility_scope) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id", UUID.class, "kc-" + username, username,
				email, firstName, lastName, status, facilityScope);
	}

	private UUID insertRole(String name, String displayName) {
		return jdbc.queryForObject("INSERT INTO roles (name, display_name) VALUES (?, ?) RETURNING id", UUID.class,
				name, displayName);
	}

	private void giveRole(UUID userId, UUID roleId) {
		jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", userId, roleId);
	}
}
