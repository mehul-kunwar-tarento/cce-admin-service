package org.openphc.cce.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The audit row for a change, on a real Postgres. Each test opens its own transaction (no class-level
 * rollback), because "no transaction" and "rolled back" are what is being tested.
 */
// The whole app starts (security included), so the JWT and Keycloak settings need values
@SpringBootTest(properties = { "JWT_ISSUER=http://test-keycloak/realms/cce", "JWT_AUDIENCE=cce-admin-service",
		"KEYCLOAK_BASE_URL=http://test-keycloak", "KEYCLOAK_CLIENT_SECRET=test-secret" })
@Testcontainers
class AuditWriterTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

	private static final String REMOTE_ADDRESS = "10.0.0.1";

	@Autowired
	private AuditWriter auditWriter;

	@Autowired
	private TransactionTemplate transaction;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void emptyAuditLogAndStartWithoutRequest() {
		jdbc.update("DELETE FROM audit_log");
		// The test framework puts an empty request (from 127.0.0.1) on the thread before each test; drop it so
		// a test is outside a request, as at startup, unless it sets its own
		RequestContextHolder.resetRequestAttributes();
	}

	@AfterEach
	void forgetRequest() {
		RequestContextHolder.resetRequestAttributes();
	}

	// ---- row content ----

	@Test
	void recordWritesOneRowWithEveryField() {
		UUID aliceId = UUID.randomUUID();
		MockHttpServletRequest request = requestFrom(new CurrentUser(aliceId, "alice", List.of("SUPER_ADMIN")));
		request.addHeader("X-Forwarded-For", "1.2.3.4");
		startRequest(request);

		transaction.executeWithoutResult(status -> auditWriter.record(AuditAction.ROLE_UPDATED, "ROLE", "role-1",
				Map.of("displayName", "Old"), Map.of("displayName", "New")));

		AuditAssert.exactlyOneRow(jdbc, AuditAction.ROLE_UPDATED, "role-1");
		Map<String, Object> row = onlyRow();
		assertThat(row.get("actor_id")).isEqualTo(aliceId);
		assertThat(row.get("actor_username")).isEqualTo("alice");
		assertThat(row.get("action")).isEqualTo("ROLE_UPDATED");
		assertThat(row.get("entity_type")).isEqualTo("ROLE");
		assertThat(row.get("entity_id")).isEqualTo("role-1");
		assertThat(row.get("before_name")).isEqualTo("Old");
		assertThat(row.get("after_name")).isEqualTo("New");
		assertThat(row.get("ip_address")).isEqualTo("1.2.3.4");
		assertThat(row.get("created_at")).isNotNull();
	}

	@Test
	void nullBeforeAndAfterAreStoredAsNull() {
		recordInATransaction(null, null);

		Map<String, Object> row = onlyRow();
		assertThat(row.get("before")).isNull();
		assertThat(row.get("after")).isNull();
	}

	// ---- transaction rules ----

	@Test
	void recordWithoutATransactionThrowsAndWritesNothing() {
		assertThatThrownBy(() -> auditWriter.record(AuditAction.ROLE_UPDATED, "ROLE", "role-1", null, null))
				.isInstanceOf(IllegalTransactionStateException.class);

		assertThat(rowCount()).isZero();
	}

	@Test
	void rollbackAfterRecordLeavesNoRow() {
		transaction.executeWithoutResult(status -> {
			auditWriter.record(AuditAction.ROLE_UPDATED, "ROLE", "role-1", null, null);
			// The change this row belongs to failed after the audit write
			status.setRollbackOnly();
		});

		assertThat(rowCount()).isZero();
	}

	// ---- actor ----

	@Test
	void noRequestMeansSystemActorAndNoIp() {
		recordInATransaction(null, null);

		Map<String, Object> row = onlyRow();
		assertThat(row.get("actor_username")).isEqualTo("SYSTEM");
		assertThat(row.get("actor_id")).isNull();
		assertThat(row.get("ip_address")).isNull();
	}

	@Test
	void requestWithoutLoggedInCallerIsSystem() {
		startRequest(requestFrom(null));

		recordInATransaction(null, null);

		Map<String, Object> row = onlyRow();
		assertThat(row.get("actor_username")).isEqualTo("SYSTEM");
		assertThat(row.get("actor_id")).isNull();
		assertThat(row.get("ip_address")).isEqualTo(REMOTE_ADDRESS);
	}

	// ---- IP address ----

	@Test
	void firstForwardedEntryIsTheIp() {
		assertThat(ipStoredFor("1.2.3.4, 10.0.0.1")).isEqualTo("1.2.3.4");
	}

	@Test
	void noHeaderUsesRemoteAddress() {
		assertThat(ipStoredFor(null)).isEqualTo(REMOTE_ADDRESS);
	}

	@Test
	void blankFirstEntryUsesRemoteAddress() {
		assertThat(ipStoredFor(" , 10.0.0.1")).isEqualTo(REMOTE_ADDRESS);
		assertThat(ipStoredFor(",")).isEqualTo(REMOTE_ADDRESS);
		assertThat(ipStoredFor("")).isEqualTo(REMOTE_ADDRESS);
	}

	@Test
	void tooLongFirstEntryUsesRemoteAddressAndStillSaves() {
		String sixtyCharacters = "x".repeat(60);

		assertThat(ipStoredFor(sixtyCharacters + ", 1.2.3.4")).isEqualTo(REMOTE_ADDRESS);
	}

	@Test
	void longestIpv6AddressFits() {
		String longestIpv6 = "ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255";
		assertThat(longestIpv6).hasSize(45);

		assertThat(ipStoredFor(longestIpv6)).isEqualTo(longestIpv6);
	}

	// ---- failure records ----

	@Test
	void failureRowSurvivesTheMainRollback() {
		transaction.executeWithoutResult(status -> {
			auditWriter.recordFailure(AuditAction.USER_CREATE_FAILED, "USER", "asha", "kc-123", "save user",
					"duplicate email", "keycloak user deleted");
			// The change itself failed, so its transaction is rolled back
			status.setRollbackOnly();
		});

		AuditAssert.exactlyOneRow(jdbc, AuditAction.USER_CREATE_FAILED, "asha");
		Map<String, Object> row = jdbc.queryForMap("SELECT before, after ->> 'keycloakId' AS keycloak_id,"
				+ " after ->> 'failedStep' AS failed_step, after ->> 'error' AS error,"
				+ " after ->> 'cleanup' AS cleanup FROM audit_log");
		assertThat(row.get("before")).isNull();
		assertThat(row.get("keycloak_id")).isEqualTo("kc-123");
		assertThat(row.get("failed_step")).isEqualTo("save user");
		assertThat(row.get("error")).isEqualTo("duplicate email");
		assertThat(row.get("cleanup")).isEqualTo("keycloak user deleted");
	}

	@Test
	void failureRowHasActorAndIpLikeAnyRow() {
		UUID aliceId = UUID.randomUUID();
		MockHttpServletRequest request = requestFrom(new CurrentUser(aliceId, "alice", List.of("SUPER_ADMIN")));
		request.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.1");
		startRequest(request);

		auditWriter.recordFailure(AuditAction.USER_STATUS_UPDATE_FAILED, "USER", "user-1", "kc-1",
				"disable in keycloak", "timeout", "none");

		Map<String, Object> row = onlyRow();
		assertThat(row.get("actor_id")).isEqualTo(aliceId);
		assertThat(row.get("actor_username")).isEqualTo("alice");
		assertThat(row.get("entity_type")).isEqualTo("USER");
		assertThat(row.get("ip_address")).isEqualTo("1.2.3.4");
	}

	// ---- helpers ----

	private MockHttpServletRequest requestFrom(CurrentUser user) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(REMOTE_ADDRESS);
		if (user != null) {
			CurrentUser.set(request, user);
		}
		return request;
	}

	/** Makes the request visible to code that has no request parameter, as Spring does for a real one. */
	private void startRequest(MockHttpServletRequest request) {
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

	private void recordInATransaction(Object before, Object after) {
		transaction.executeWithoutResult(
				status -> auditWriter.record(AuditAction.ROLE_UPDATED, "ROLE", "role-1", before, after));
	}

	/** Records one change with this X-Forwarded-For header (null = no header) and returns the stored IP. */
	private String ipStoredFor(String forwardedFor) {
		jdbc.update("DELETE FROM audit_log");
		MockHttpServletRequest request = requestFrom(new CurrentUser(UUID.randomUUID(), "alice", List.of()));
		if (forwardedFor != null) {
			request.addHeader("X-Forwarded-For", forwardedFor);
		}
		startRequest(request);

		recordInATransaction(null, null);

		return (String) onlyRow().get("ip_address");
	}

	private Map<String, Object> onlyRow() {
		return jdbc.queryForMap("SELECT *, before ->> 'displayName' AS before_name,"
				+ " after ->> 'displayName' AS after_name FROM audit_log");
	}

	private int rowCount() {
		return jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class);
	}
}
