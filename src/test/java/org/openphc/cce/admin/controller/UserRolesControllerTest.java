package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditAssert;
import org.openphc.cce.admin.client.GatewaySyncClient;
import org.openphc.cce.admin.client.KeycloakClient;
import org.openphc.cce.admin.security.TestTokens;
import org.skyscreamer.jsonassert.JSONAssert;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;

/**
 * PUT /admin/v1/users/{id}/roles called the way a client calls it. Each test runs in a transaction that is
 * rolled back. Keycloak failures need real commits: UserRolesFailureTest; parallel requests: UserRolesRaceTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class UserRolesControllerTest {

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

	@Autowired
	private EntityManager entityManager;

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

	@Test
	void onlyTheDifferenceReachesKeycloak() throws Exception {
		// [NURSE, PHARMACIST] → [PHARMACIST, DOCTOR]: give DOCTOR, take NURSE, leave PHARMACIST
		putRoles(alice, body(pharmacist, doctor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.roles[*].name").value(contains("DOCTOR", "PHARMACIST")));

		InOrder order = inOrder(keycloakClient);
		order.verify(keycloakClient).assignRealmRoles("kc-alice", List.of("DOCTOR"));
		order.verify(keycloakClient).removeRealmRoles("kc-alice", List.of("NURSE"));
		verifyNoMoreInteractions(keycloakClient);

		entityManager.flush();
		assertThat(roleNamesOf(alice)).containsExactly("DOCTOR", "PHARMACIST");
		AuditAssert.exactlyOneRow(jdbc, AuditAction.USER_ROLES_UPDATED, alice.toString());
		Map<String, Object> audit = jdbc.queryForMap("SELECT before::text AS before, after::text AS after"
				+ " FROM audit_log WHERE action = 'USER_ROLES_UPDATED'");
		JSONAssert.assertEquals("{\"roles\":[\"NURSE\",\"PHARMACIST\"]}", (String) audit.get("before"), true);
		JSONAssert.assertEquals("{\"roles\":[\"DOCTOR\",\"PHARMACIST\"]}", (String) audit.get("after"), true);
	}

	@Test
	void onlyAddingCallsNoRemove() throws Exception {
		putRoles(alice, body(nurse, pharmacist, doctor)).andExpect(status().isOk());

		verify(keycloakClient).assignRealmRoles("kc-alice", List.of("DOCTOR"));
		verifyNoMoreInteractions(keycloakClient);
	}

	@Test
	void onlyRemovingCallsNoAssign() throws Exception {
		putRoles(alice, body(nurse)).andExpect(status().isOk());

		verify(keycloakClient).removeRealmRoles("kc-alice", List.of("PHARMACIST"));
		verifyNoMoreInteractions(keycloakClient);
		entityManager.flush();
		assertThat(roleNamesOf(alice)).containsExactly("NURSE");
	}

	@Test
	void sameSetWritesNothing() throws Exception {
		// Another order and a repeated id: still the same set
		putRoles(alice, body(pharmacist, nurse, nurse))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.roles[*].name").value(contains("NURSE", "PHARMACIST")));

		verifyNoInteractions(keycloakClient);
		entityManager.flush();
		assertThat(auditRows("USER_ROLES_UPDATED")).isZero();
	}

	@Test
	void inactiveUsersRolesCanBeChanged() throws Exception {
		UUID bob = insertUser("bob", "INACTIVE");
		giveRole(bob, nurse);

		putRoles(bob, body(doctor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("INACTIVE"))
				.andExpect(jsonPath("$.data.roles[*].name").value(contains("DOCTOR")));
	}

	@Test
	void superAdminCanBeGiven() throws Exception {
		UUID superAdmin = jdbc.queryForObject("SELECT id FROM roles WHERE name = 'SUPER_ADMIN'", UUID.class);

		putRoles(alice, body(nurse, pharmacist, superAdmin)).andExpect(status().isOk());

		verify(keycloakClient).assignRealmRoles("kc-alice", List.of("SUPER_ADMIN"));
	}

	@Test
	void emptyListIsRefused() throws Exception {
		expectRefused(alice, "{\"roleIds\":[]}", 400, "VALIDATION_ERROR", "Invalid request: roleIds must not be empty.");
	}

	@Test
	void unknownRoleIdIsRefused() throws Exception {
		UUID unknown = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

		expectRefused(alice, body(nurse, unknown), 400, "VALIDATION_ERROR",
				"Unknown role id(s): " + unknown + ". Nothing was changed.");
	}

	@Test
	void ownRolesAreRefused() throws Exception {
		expectRefused(admin, body(doctor), 400, "VALIDATION_ERROR",
				"You can't change your own roles. Ask another administrator.");
	}

	@Test
	void unknownUserIsNotFound() throws Exception {
		UUID unknown = UUID.randomUUID();

		expectRefused(unknown, body(doctor), 404, "USER_NOT_FOUND", "No user with id " + unknown + ".");
	}

	/** The given error, Keycloak never called, alice's roles as they were and no audit row. */
	private void expectRefused(UUID id, String body, int httpStatus, String code, String message) throws Exception {
		putRoles(id, body)
				.andExpect(status().is(httpStatus))
				.andExpect(jsonPath("$.error.code").value(code))
				.andExpect(jsonPath("$.error.message").value(message));

		verifyNoInteractions(keycloakClient);
		entityManager.flush();
		assertThat(roleNamesOf(alice)).containsExactly("NURSE", "PHARMACIST");
		assertThat(auditRows("USER_ROLES_UPDATED")).isZero();
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
