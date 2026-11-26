package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.client.GatewaySyncClient;
import org.openphc.cce.admin.client.GatewaySyncException;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.openphc.cce.admin.security.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DELETE /roles and the gateway sync, with real commits: whether the rows were gone before the sync, kept
 * gone after a failed one, and what a user added in between does, can't be seen inside a test transaction.
 * Each test cleans up after itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RoleDeleteSyncTest {

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
	private DataSource dataSource;

	@MockitoBean
	private GatewaySyncClient gatewaySyncClient;

	// The real repository, so a test can make the "anyone holds it?" check miss
	@MockitoSpyBean
	private UserRoleRepository userRoleRepository;

	private UUID roleId;

	@BeforeEach
	void insertAdminAndRole() {
		jdbc.update("INSERT INTO users (keycloak_id, username, email, status) VALUES (?, ?, ?, ?)",
				"kc-admin", "admin", "admin@x.com", "ACTIVE");
		roleId = jdbc.queryForObject("INSERT INTO roles (name, display_name) VALUES ('FACILITY_ADMIN', 'Facility admin')"
				+ " RETURNING id", UUID.class);
		jdbc.update("INSERT INTO api_permissions (permission_name, http_method, uri_pattern)"
				+ " VALUES ('FACILITY_ADMIN', 'GET', '/admin/v1/me/menus')");
	}

	@AfterEach
	void removeTestRows() {
		jdbc.update("DELETE FROM audit_log");
		jdbc.update("DELETE FROM user_roles");
		jdbc.update("DELETE FROM api_permissions WHERE permission_name = 'FACILITY_ADMIN'");
		jdbc.update("DELETE FROM roles WHERE name = 'FACILITY_ADMIN'");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void syncRunsAfterCommit() throws Exception {
		// What the gateway would see when it is called: it reads over a connection of its own
		List<Integer> rowsSeenByGateway = new ArrayList<>();
		doAnswer(call -> {
			rowsSeenByGateway.add(committedRowsOf("FACILITY_ADMIN"));
			return null;
		}).when(gatewaySyncClient).sync(any(UUID.class));

		mockMvc.perform(deleteRole()).andExpect(status().isNoContent());

		assertThat(rowsSeenByGateway).containsExactly(0);
	}

	@Test
	void failedSyncIs502AndTheDeleteStays() throws Exception {
		doThrow(new GatewaySyncException("Gateway not reachable at http://test-gateway."))
				.when(gatewaySyncClient).sync(any(UUID.class));

		mockMvc.perform(deleteRole())
				.andExpect(status().isBadGateway())
				.andExpect(content().json("{\"error\":{\"code\":\"KEYCLOAK_SYNC_FAILED\",\"message\":\"Role deleted."
						+ " Keycloak still has realm role FACILITY_ADMIN until the next successful sync of any role."
						+ " No user in this service holds it.\"}}", JsonCompareMode.STRICT));

		assertThat(count("SELECT count(*) FROM roles WHERE name = 'FACILITY_ADMIN'")).isZero();
		assertThat(committedRowsOf("FACILITY_ADMIN")).isZero();
		assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'ROLE_DELETED'")).isEqualTo(1);
	}

	@Test
	void userAddedInBetweenIs409() throws Exception {
		// Ravi holds the role, but the check runs as if he got it only after the check
		UUID ravi = jdbc.queryForObject("INSERT INTO users (keycloak_id, username, email, status)"
				+ " VALUES ('kc-ravi', 'ravi', 'ravi@x.com', 'ACTIVE') RETURNING id", UUID.class);
		jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", ravi, roleId);
		doReturn(false).when(userRoleRepository).existsByRoleId(roleId);

		mockMvc.perform(deleteRole())
				.andExpect(status().isConflict())
				.andExpect(content().json("{\"error\":{\"code\":\"ROLE_IN_USE\",\"message\":\"'FACILITY_ADMIN' is still"
						+ " held by at least one user (active or not). Remove it from those users first."
						+ " Nothing was deleted.\"}}", JsonCompareMode.STRICT));

		assertThat(count("SELECT count(*) FROM roles WHERE name = 'FACILITY_ADMIN'")).isEqualTo(1);
		assertThat(committedRowsOf("FACILITY_ADMIN")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_log")).isZero();
		verifyNoInteractions(gatewaySyncClient);
	}

	private RequestBuilder deleteRole() {
		return delete("/admin/v1/roles/" + roleId).header("Authorization", "Bearer " + tokens.forUser("kc-admin"));
	}

	/** Counts the role's permission rows over a fresh connection, which sees only committed rows. */
	private int committedRowsOf(String name) throws Exception {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(
						"SELECT count(*) FROM api_permissions WHERE permission_name = '" + name + "'")) {
			result.next();
			return result.getInt(1);
		}
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}
}
