package org.openphc.cce.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.jdbc.core.JdbcTemplate;

/** One audit row per change: the check every feature test uses. */
public final class AuditAssert {

	private AuditAssert() {
	}

	public static void exactlyOneRow(JdbcTemplate jdbc, AuditAction action, String entityId) {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?",
				Integer.class, action.name(), entityId);
		assertThat(count).as("audit rows for %s %s", action, entityId).isEqualTo(1);
	}
}
