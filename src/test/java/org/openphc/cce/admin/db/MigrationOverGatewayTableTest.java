package org.openphc.cce.admin.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A database that looks like a server: gateway-service's api_permissions table and its rows exist
 * before our migrations run. Flyway is run by hand here (the app does the same at startup), so the
 * gateway seed can be loaded first.
 */
@Testcontainers
class MigrationOverGatewayTableTest {

	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
			.withInitScript("db/gateway-api-permissions-seed.sql");

	private static JdbcTemplate jdbc;
	private static Flyway flyway;
	private static List<String> gatewayRowsBefore;
	private static MigrateResult firstMigration;

	@BeforeAll
	static void loadGatewaySeedThenMigrate() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		// Same Flyway settings as application.yml
		flyway = Flyway.configure()
				.dataSource(dataSource)
				.baselineOnMigrate(true)
				.baselineVersion("0")
				.table("flyway_schema_history_admin")
				.load();

		gatewayRowsBefore = gatewayRows();
		firstMigration = flyway.migrate();
	}

	@Test
	void gatewaySeedWasLoadedFirst() {
		assertThat(gatewayRowsBefore).hasSize(15);
	}

	@Test
	void migrationSucceedsOverTheExistingTable() {
		assertThat(firstMigration.success).isTrue();
		assertThat(firstMigration.migrationsExecuted).isEqualTo(2);
	}

	@Test
	void gatewayRowsAreUnchanged() {
		assertThat(gatewayRows()).containsExactlyElementsOf(gatewayRowsBefore);
	}

	@Test
	void superAdminRowsAreAddedOnceAfterTheGatewayRows() {
		List<Long> ids = jdbc.queryForList(
				"SELECT id FROM api_permissions WHERE permission_name = 'SUPER_ADMIN' ORDER BY id", Long.class);
		assertThat(ids).containsExactly(16L, 17L, 18L, 19L, 20L);
	}

	@Test
	void runningAgainAddsNothing() {
		Map<String, Integer> countsBefore = rowCounts();

		MigrateResult secondMigration = flyway.migrate();

		assertThat(secondMigration.success).isTrue();
		assertThat(secondMigration.migrationsExecuted).isZero();
		assertThat(rowCounts()).isEqualTo(countsBefore);
	}

	// ---- helpers ----

	/** Rows that aren't ours, as "id|permission_name|http_method|uri_pattern|description|resource_category|created_at". */
	private static List<String> gatewayRows() {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT id, permission_name, http_method, uri_pattern, description, resource_category, created_at"
						+ " FROM api_permissions WHERE permission_name <> 'SUPER_ADMIN' ORDER BY id");
		List<String> lines = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			String line = row.get("id") + "|" + row.get("permission_name") + "|" + row.get("http_method")
					+ "|" + row.get("uri_pattern") + "|" + row.get("description")
					+ "|" + row.get("resource_category") + "|" + row.get("created_at");
			lines.add(line);
		}
		return lines;
	}

	/** Row count of each of our 7 tables. */
	private static Map<String, Integer> rowCounts() {
		String[] tables = { "users", "roles", "menus", "role_menus", "user_roles", "api_permissions", "audit_log" };
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String table : tables) {
			Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
			counts.put(table, count);
		}
		return counts;
	}
}
