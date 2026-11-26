package org.openphc.cce.admin.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.AntPathMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the Flyway migrations on a real, throwaway Postgres (Docker) and checks the result.
 * Each test runs in a transaction that is rolled back, so tests never see each other's rows.
 */
// The whole app starts (security included), so the JWT, Keycloak and gateway settings need values
@SpringBootTest(properties = { "JWT_ISSUER=http://test-keycloak/realms/cce", "JWT_AUDIENCE=cce-admin-service",
		"KEYCLOAK_BASE_URL=http://test-keycloak", "KEYCLOAK_CLIENT_SECRET=test-secret",
		"GATEWAY_BASE_URL=http://test-gateway" })
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
				"SELECT success FROM flyway_schema_history_admin WHERE version = '1'", Boolean.class);
		assertThat(success).isTrue();
	}

	@Test
	void allSevenTablesExist() {
		List<String> tables = jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
						+ " AND table_name <> 'flyway_schema_history_admin'", String.class);
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

	// ---- seed ----

	@Test
	void flywayAppliedVersion2() {
		Boolean success = jdbc.queryForObject(
				"SELECT success FROM flyway_schema_history_admin WHERE version = '2'", Boolean.class);
		assertThat(success).isTrue();
	}

	@Test
	void seedHasTheTwelveMenusExactly() {
		// key | label | path | type | parent key | sort_order | icon   ("-" = none)
		assertThat(menuRowsAsText()).containsExactly(
				"dashboard|Dashboard|/|NAV|-|10|ChartBarIcon",
				"compliance|Compliance|/compliance|NAV|-|20|ClipboardDocumentCheckIcon",
				"protocol-detail|Protocol analytics|/compliance/protocols/:id|DETAIL|compliance|21|-",
				"facilities|Facilities|/facilities|NAV|-|30|BuildingOffice2Icon",
				"practitioners|Practitioners|/practitioners|NAV|-|40|UserGroupIcon",
				"deviations|Deviations|/deviations|NAV|-|50|ExclamationTriangleIcon",
				"patients|Patients|/compliance/patients|NAV|-|60|ClipboardDocumentCheckIcon",
				"patient-detail|Patient detail|/compliance/patients/:id|DETAIL|patients|61|-",
				"intelligence|Intelligence|/intelligence|NAV|-|70|BoltIcon",
				"events|Events|/events|NAV|-|80|SignalIcon",
				"ingestion|Ingestion|/ingestion|NAV|-|90|CogIcon",
				"exports|Exports|/exports|NAV|-|100|ArrowDownTrayIcon");
	}

	@Test
	void allSeededMenusAreActive() {
		Integer inactive = jdbc.queryForObject("SELECT count(*) FROM menus WHERE is_active = false", Integer.class);
		assertThat(inactive).isZero();
	}

	@Test
	void superAdminIsTheOnlyRoleAndIsASystemRole() {
		List<Map<String, Object>> roles = jdbc.queryForList("SELECT name, display_name, is_system FROM roles");
		assertThat(roles).hasSize(1);
		assertThat(roles.get(0).get("name")).isEqualTo("SUPER_ADMIN");
		assertThat(roles.get(0).get("display_name")).isEqualTo("Super Admin");
		assertThat(roles.get(0).get("is_system")).isEqualTo(true);
	}

	@Test
	void superAdminHasFivePermissionRowsForTheWholeAdminApi() {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT http_method, uri_pattern FROM api_permissions WHERE permission_name = 'SUPER_ADMIN'");
		List<String> methods = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			methods.add((String) row.get("http_method"));
			assertThat(row.get("uri_pattern")).isEqualTo("/admin/v1/**");
		}
		assertThat(methods).containsExactlyInAnyOrder("GET", "POST", "PUT", "PATCH", "DELETE");
	}

	@Test
	void superAdminGetRowCoversMeMenus() {
		// Same pattern matching the gateway uses
		String pattern = jdbc.queryForObject(
				"SELECT uri_pattern FROM api_permissions WHERE permission_name = 'SUPER_ADMIN' AND http_method = 'GET'",
				String.class);
		assertThat(new AntPathMatcher().match(pattern, "/admin/v1/me/menus")).isTrue();
	}

	@Test
	void tablesFilledLaterAreEmpty() {
		String[] tables = { "users", "user_roles", "role_menus", "audit_log" };
		for (String table : tables) {
			Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
			assertThat(count).as(table).isZero();
		}
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

	/** Every menu as "key|label|path|type|parent key|sort_order|icon", in sidebar order; "-" for none. */
	private List<String> menuRowsAsText() {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT m.key, m.label, m.path, m.type, p.key AS parent_key, m.sort_order, m.icon"
						+ " FROM menus m LEFT JOIN menus p ON p.id = m.parent_id ORDER BY m.sort_order");
		List<String> lines = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			String line = row.get("key") + "|" + row.get("label") + "|" + row.get("path") + "|" + row.get("type")
					+ "|" + textOrDash(row.get("parent_key")) + "|" + row.get("sort_order")
					+ "|" + textOrDash(row.get("icon"));
			lines.add(line);
		}
		return lines;
	}

	private String textOrDash(Object value) {
		if (value == null) {
			return "-";
		}
		return value.toString();
	}

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
