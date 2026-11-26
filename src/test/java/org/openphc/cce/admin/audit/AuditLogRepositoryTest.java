package org.openphc.cce.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.entity.AuditLog;
import org.openphc.cce.admin.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.repository.CrudRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

/**
 * The audit table from Java: rows go in and read back, and nothing can change or delete them.
 * Each test runs in a transaction that is rolled back, so tests never see each other's rows.
 */
// The whole app starts (security included), so the JWT and Keycloak settings need values
@SpringBootTest(properties = { "JWT_ISSUER=http://test-keycloak/realms/cce", "JWT_AUDIENCE=cce-admin-service",
		"KEYCLOAK_BASE_URL=http://test-keycloak", "KEYCLOAK_CLIENT_SECRET=test-secret" })
@Testcontainers
@Transactional
class AuditLogRepositoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

	@Autowired
	private AuditLogRepository repository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void savedRowReadsBackWithJson() throws Exception {
		UUID actorId = UUID.randomUUID();
		JsonNode before = objectMapper.readTree("{\"displayName\":\"Old\"}");
		JsonNode after = objectMapper.readTree("{\"displayName\":\"New\",\"menus\":[\"dashboard\"]}");
		AuditLog saved = repository.save(new AuditLog(actorId, "alice", AuditAction.ROLE_UPDATED, "ROLE",
				"role-1", before, after, "1.2.3.4"));

		// Send the INSERT and forget the cached object, so the read below really comes from the database
		entityManager.flush();
		entityManager.clear();
		AuditLog read = repository.findById(saved.getId()).orElseThrow();

		assertThat(read.getActorId()).isEqualTo(actorId);
		assertThat(read.getActorUsername()).isEqualTo("alice");
		assertThat(read.getAction()).isEqualTo(AuditAction.ROLE_UPDATED);
		assertThat(read.getEntityType()).isEqualTo("ROLE");
		assertThat(read.getEntityId()).isEqualTo("role-1");
		assertThat(read.getBefore()).isEqualTo(before);
		assertThat(read.getAfter()).isEqualTo(after);
		assertThat(read.getIpAddress()).isEqualTo("1.2.3.4");
		assertThat(read.getCreatedAt()).isNotNull();
	}

	@Test
	void jsonIsStoredAsJsonNotAsText() throws Exception {
		JsonNode after = objectMapper.readTree("{\"name\":\"FACILITY_ADMIN\"}");
		AuditLog saved = repository.save(new AuditLog(null, "SYSTEM", AuditAction.ROLE_UPDATED, "ROLE",
				"role-1", null, after, null));
		entityManager.flush();

		// Postgres can read a field inside it, which only works if a JSON object was stored
		String name = jdbc.queryForObject("SELECT after ->> 'name' FROM audit_log WHERE id = ?", String.class,
				saved.getId());
		assertThat(name).isEqualTo("FACILITY_ADMIN");
	}

	@Test
	void repositoryHasNoUpdateOrDeleteMethod() {
		assertThat(CrudRepository.class.isAssignableFrom(AuditLogRepository.class)).isFalse();

		Method[] methods = AuditLogRepository.class.getMethods();
		for (Method method : methods) {
			String name = method.getName();
			assertThat(name).doesNotStartWith("delete");
			assertThat(name).doesNotStartWith("update");
			assertThat(name).doesNotStartWith("remove");
		}
	}

	@Test
	void changingASavedRowWritesNothing() throws Exception {
		AuditLog saved = repository.save(new AuditLog(null, "SYSTEM", AuditAction.ROLE_UPDATED, "ROLE",
				"role-1", null, null, null));
		entityManager.flush();
		entityManager.clear();

		// The entity has no setters; force a change the way a careless future edit might
		AuditLog loaded = repository.findById(saved.getId()).orElseThrow();
		ReflectionTestUtils.setField(loaded, "actorUsername", "mallory");
		repository.save(loaded);
		entityManager.flush();

		String inDatabase = jdbc.queryForObject("SELECT actor_username FROM audit_log WHERE id = ?", String.class,
				saved.getId());
		assertThat(inDatabase).isEqualTo("SYSTEM");
	}
}
