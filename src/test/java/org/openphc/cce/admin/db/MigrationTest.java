package org.openphc.cce.admin.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the Flyway migrations on a real, throwaway Postgres (Docker) and checks the result.
 * Each test runs in a transaction that is rolled back, so tests never see each other's rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Transactional
class MigrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

	@Autowired
	private JdbcTemplate jdbc;

	// ---- schema exists ----

	@Test
	void flywayAppliedVersion1() {
		Boolean success = jdbc.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);
		assertThat(success).isTrue();
	}

	@Test
	void allSevenTablesExist() {
		List<String> tables = jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
						+ " AND table_name <> 'flyway_schema_history'", String.class);
		assertThat(tables).containsExactlyInAnyOrder(
				"users", "roles", "menus", "role_menus", "user_roles", "api_permissions", "audit_log");
	}

	@Test
	void pgcryptoExtensionExists() {
		Integer count = jdbc.queryForObject(
				"SELECT count(*) FROM pg_extension WHERE extname = 'pgcrypto'", Integer.class);
		assertThat(count).isEqualTo(1);
	}

	// ---- primary keys ----

	@Test
	void uuidPrimaryKeysDefaultToGenRandomUuid() {
		String[] tables = { "users", "roles", "menus", "audit_log" };
		for (String table : tables) {
			assertThat(columnType(table, "id")).as(table + ".id type").isEqualTo("uuid");
			assertThat(columnDefault(table, "id")).as(table + ".id default").isEqualTo("gen_random_uuid()");
		}
	}

	@Test
	void apiPermissionsIdIsBigintFromASequence() {
		assertThat(columnType("api_permissions", "id")).isEqualTo("bigint");
		assertThat(columnDefault("api_permissions", "id")).startsWith("nextval(");
	}

	// ---- the database rejects bad data ----

	@Test
	void rejectsUnknownUserStatus() {
		assertRejected(() -> insertUser("kc-1", "asha", "asha@x.com", "FOO"), "ck_users_status");
	}

	@Test
	void rejectsDuplicateRoleName() {
		insertRole("FACILITY_ADMIN");
		assertRejected(() -> insertRole("FACILITY_ADMIN"), "uk_roles_name");
	}

	@Test
	void rejectsLowercaseRoleName() {
		assertRejected(() -> insertRole("facility_admin"), "ck_roles_name");
	}

	@Test
	void rejectsEmailThatDiffersOnlyInCase() {
		insertUser("kc-1", "asha", "a@x.com", "ACTIVE");
		assertRejected(() -> insertUser("kc-2", "ravi", "A@x.com", "ACTIVE"), "uk_users_email_lower");
	}

	@Test
	void rejectsDuplicateMenuKey() {
		insertMenu("reports");
		assertRejected(() -> insertMenu("reports"), "uk_menus_key");
	}

	@Test
	void rejectsSameRoleTwiceForOneUser() {
		UUID userId = insertUser("kc-1", "asha", "asha@x.com", "ACTIVE");
		UUID roleId = insertRole("FACILITY_ADMIN");
		insertUserRole(userId, roleId);
		assertRejected(() -> insertUserRole(userId, roleId), "pk_user_roles");
	}

	// ---- helpers ----

	private String columnType(String table, String column) {
		return jdbc.queryForObject(
				"SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
				String.class, table, column);
	}

	private String columnDefault(String table, String column) {
		return jdbc.queryForObject(
				"SELECT column_default FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
				String.class, table, column);
	}

	private UUID insertUser(String keycloakId, String username, String email, String status) {
		return jdbc.queryForObject(
				"INSERT INTO users (keycloak_id, username, email, status) VALUES (?, ?, ?, ?) RETURNING id",
				UUID.class, keycloakId, username, email, status);
	}

	private UUID insertRole(String name) {
		return jdbc.queryForObject(
				"INSERT INTO roles (name, display_name) VALUES (?, ?) RETURNING id",
				UUID.class, name, "Some role");
	}

	private UUID insertMenu(String key) {
		return jdbc.queryForObject(
				"INSERT INTO menus (key, label, path, type, sort_order) VALUES (?, ?, ?, 'NAV', 10) RETURNING id",
				UUID.class, key, "Some menu", "/" + key);
	}

	private void insertUserRole(UUID userId, UUID roleId) {
		jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", userId, roleId);
	}

	/** The insert must fail, and the error must name the constraint that stopped it. */
	private void assertRejected(Runnable insert, String constraintName) {
		assertThatThrownBy(insert::run)
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining(constraintName);
	}
}
