package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * POST /roles and the gateway sync, with real commits: whether the rows were committed before the sync,
 * and kept after a failed one, can't be seen inside a test transaction. Each test cleans up after itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RoleCreateSyncTest {

	private static final String BODY = "{\"name\":\"FACILITY_ADMIN\",\"displayName\":\"Facility admin\"}";

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

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private GatewaySyncClient gatewaySyncClient;

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
	void syncRunsAfterCommit() throws Exception {
		// What the gateway would see when it is called: it reads over a connection of its own
		List<Integer> rowsSeenByGateway = new ArrayList<>();
		doAnswer(call -> {
			rowsSeenByGateway.add(committedRowsOf("FACILITY_ADMIN"));
			return null;
		}).when(gatewaySyncClient).sync(any(UUID.class));

		mockMvc.perform(postRole()).andExpect(status().isCreated());

		assertThat(rowsSeenByGateway).containsExactly(1);
	}

	@Test
	void failedSyncIs502AndKeepsEverything() throws Exception {
		List<String> reasons = new ArrayList<>();
		reasons.add("Gateway reloaded its rules, but Keycloak role sync failed for: FACILITY_ADMIN.");
		reasons.add("Gateway sync error: Keycloak is down.");
		reasons.add("Gateway did not answer within 10s.");

		for (String reason : reasons) {
			doThrow(new GatewaySyncException(reason)).when(gatewaySyncClient).sync(any(UUID.class));

			String body = mockMvc.perform(postRole())
					.andExpect(status().isBadGateway())
					.andReturn().getResponse().getContentAsString();

			UUID id = jdbc.queryForObject("SELECT id FROM roles WHERE name = 'FACILITY_ADMIN'", UUID.class);
			JsonNode error = objectMapper.readTree(body).path("error");
			assertThat(error.path("code").asText()).isEqualTo("KEYCLOAK_SYNC_FAILED");
			assertThat(error.path("message").asText())
					.isEqualTo("Role created. " + reason + " Retry with POST /admin/v1/roles/" + id + "/sync.");
			assertThat(committedRowsOf("FACILITY_ADMIN")).as("the sidebar row stays").isEqualTo(1);
			Integer auditRows = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ROLE_CREATED'"
					+ " AND entity_id = ?", Integer.class, id.toString());
			assertThat(auditRows).as("the audit row stays").isEqualTo(1);

			removeTestRows();
			insertAdmin();
		}
	}

	private org.springframework.test.web.servlet.RequestBuilder postRole() {
		return post("/admin/v1/roles")
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY);
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
}
