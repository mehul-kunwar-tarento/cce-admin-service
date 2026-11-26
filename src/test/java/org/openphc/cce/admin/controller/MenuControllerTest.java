package org.openphc.cce.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditAssert;
import org.openphc.cce.admin.security.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

/**
 * /admin/v1/menus called the way a client calls it: a signed token, an ACTIVE local user, the real
 * seed. Each test runs in a transaction that is rolled back, so tests never see each other's rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class MenuControllerTest {

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
	private ObjectMapper objectMapper;

	@Autowired
	private EntityManager entityManager;

	@BeforeEach
	void insertAdmin() {
		jdbc.update("INSERT INTO users (keycloak_id, username, email, status) VALUES (?, ?, ?, ?)",
				"kc-admin", "admin", "admin@x.com", "ACTIVE");
	}

	// ---- GET /menus ----

	@Test
	void listIsTheSeedTree() throws Exception {
		getMenus()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].key").value(contains("dashboard", "compliance", "facilities",
						"practitioners", "deviations", "patients", "intelligence", "events", "ingestion", "exports")))
				.andExpect(jsonPath("$.data[?(@.key == 'compliance')].children[*].key")
						.value(contains("protocol-detail")))
				.andExpect(jsonPath("$.data[?(@.key == 'patients')].children[*].key")
						.value(contains("patient-detail")))
				// Only those two have children, so every other NAV has an empty list
				.andExpect(jsonPath("$.data[*].children[*].key")
						.value(containsInAnyOrder("protocol-detail", "patient-detail")))
				.andExpect(jsonPath("$.pagination").doesNotExist());
	}

	@Test
	void inactiveMenusAreListed() throws Exception {
		jdbc.update("UPDATE menus SET is_active = false WHERE key = 'events'");

		getMenus()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[7].key").value("events"))
				.andExpect(jsonPath("$.data[7].isActive").value(false))
				.andExpect(jsonPath("$.data[6].isActive").value(true));
	}

	@Test
	void sameSortOrderIsOrderedByKey() throws Exception {
		// Inserted in reverse, so only the key can put them in order
		insertNav("tie-e", 15);
		insertNav("tie-d", 15);
		insertNav("tie-c", 15);
		insertNav("tie-b", 15);
		insertNav("tie-a", 15);

		getMenus()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0:7].key").value(contains("dashboard", "tie-a", "tie-b", "tie-c",
						"tie-d", "tie-e", "compliance")));
	}

	@Test
	void nodeHasEveryField() throws Exception {
		UUID patientsId = jdbc.queryForObject("SELECT id FROM menus WHERE key = 'patients'", UUID.class);
		UUID patientDetailId = jdbc.queryForObject("SELECT id FROM menus WHERE key = 'patient-detail'", UUID.class);

		// Patients is the 6th NAV; its only child is the patient detail page
		getMenus()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[5].key").value("patients"))
				.andExpect(jsonPath("$.data[5].children[0].id").value(patientDetailId.toString()))
				.andExpect(jsonPath("$.data[5].children[0].key").value("patient-detail"))
				.andExpect(jsonPath("$.data[5].children[0].label").value("Patient detail"))
				.andExpect(jsonPath("$.data[5].children[0].path").value("/compliance/patients/:id"))
				.andExpect(jsonPath("$.data[5].children[0].icon").value(nullValue()))
				.andExpect(jsonPath("$.data[5].children[0].type").value("DETAIL"))
				.andExpect(jsonPath("$.data[5].children[0].parentId").value(patientsId.toString()))
				.andExpect(jsonPath("$.data[5].children[0].sortOrder").value(61))
				.andExpect(jsonPath("$.data[5].children[0].isActive").value(true))
				.andExpect(jsonPath("$.data[5].children[0].children").value(empty()));
	}

	// ---- POST /menus: accepted ----

	@Test
	void createNavIs201WithOneAuditRow() throws Exception {
		String body = postMenu(nav("reports"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.key").value("reports"))
				.andExpect(jsonPath("$.data.label").value("Reports"))
				.andExpect(jsonPath("$.data.path").value("/reports"))
				.andExpect(jsonPath("$.data.icon").value("DocumentIcon"))
				.andExpect(jsonPath("$.data.type").value("NAV"))
				.andExpect(jsonPath("$.data.parentId").value(nullValue()))
				.andExpect(jsonPath("$.data.sortOrder").value(110))
				.andExpect(jsonPath("$.data.isActive").value(true))
				.andExpect(jsonPath("$.data.children").value(empty()))
				.andReturn().getResponse().getContentAsString();
		String id = objectMapper.readTree(body).path("data").path("id").asText();
		sendPendingWrites();

		Integer rows = jdbc.queryForObject("SELECT count(*) FROM menus WHERE id = ?::uuid AND key = 'reports'",
				Integer.class, id);
		assertThat(rows).isEqualTo(1);
		AuditAssert.exactlyOneRow(jdbc, AuditAction.MENU_CREATED, id);
		Map<String, Object> audit = jdbc.queryForMap("SELECT entity_type, actor_username, before,"
				+ " after ->> 'key' AS after_key, after ->> 'sortOrder' AS after_sort FROM audit_log WHERE entity_id = ?", id);
		assertThat(audit.get("entity_type")).isEqualTo("MENU");
		assertThat(audit.get("actor_username")).isEqualTo("admin");
		assertThat(audit.get("before")).isNull();
		assertThat(audit.get("after_key")).isEqualTo("reports");
		assertThat(audit.get("after_sort")).isEqualTo("110");
	}

	@Test
	void createDetailUnderANav() throws Exception {
		UUID complianceId = menuId("compliance");

		postMenu(detail("site-detail", complianceId))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.type").value("DETAIL"))
				.andExpect(jsonPath("$.data.parentId").value(complianceId.toString()));

		// It shows up under its parent in the tree
		getMenus()
				.andExpect(jsonPath("$.data[?(@.key == 'compliance')].children[*].key")
						.value(contains("protocol-detail", "site-detail")));
	}

	@Test
	void missingIsActiveMeansActive() throws Exception {
		Map<String, Object> body = nav("reports");
		body.remove("isActive");

		postMenu(body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.isActive").value(true));
	}

	@Test
	void inactiveCanBeCreated() throws Exception {
		Map<String, Object> body = nav("reports");
		body.put("isActive", false);

		postMenu(body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.isActive").value(false));
	}

	@Test
	void blankIconIsStoredAsNoIcon() throws Exception {
		Map<String, Object> body = nav("reports");
		body.put("icon", "   ");

		postMenu(body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.icon").value(nullValue()));
		sendPendingWrites();
		String icon = jdbc.queryForObject("SELECT icon FROM menus WHERE key = 'reports'", String.class);
		assertThat(icon).isNull();
	}

	// ---- POST /menus: rejected by the shape checks ----

	@Test
	void missingLabelIs400() throws Exception {
		Map<String, Object> body = nav("reports");
		body.remove("label");
		expectRejected(body, "Invalid request: label must not be blank.");
	}

	@Test
	void missingPathIs400() throws Exception {
		Map<String, Object> body = nav("reports");
		body.remove("path");
		expectRejected(body, "Invalid request: path must not be blank.");
	}

	@Test
	void missingTypeIs400() throws Exception {
		Map<String, Object> body = nav("reports");
		body.remove("type");
		expectRejected(body, "Invalid request: type must not be null.");
	}

	@Test
	void unknownTypeIs400() throws Exception {
		Map<String, Object> body = nav("reports");
		body.put("type", "MENU");
		expectRejected(body, "Invalid request: type must be NAV or DETAIL.");
	}

	@Test
	void missingSortOrderIs400() throws Exception {
		Map<String, Object> body = nav("reports");
		body.remove("sortOrder");
		expectRejected(body, "Invalid request: sortOrder must not be null.");
	}

	@Test
	void badKeyFormatIs400() throws Exception {
		expectRejected(nav("Patient_Detail"), "Invalid request: key must be lowercase letters, digits and hyphens,"
				+ " starting with a letter (e.g. patient-detail).");
	}

	@Test
	void blankLabelIs400() throws Exception {
		Map<String, Object> body = nav("reports");
		body.put("label", "   ");
		expectRejected(body, "Invalid request: label must not be blank.");
	}

	@Test
	void tooLongLabelIs400() throws Exception {
		Map<String, Object> body = nav("reports");
		body.put("label", "x".repeat(256));
		expectRejected(body, "Invalid request: label size must be between 0 and 255.");
	}

	@Test
	void everyBadFieldIsNamedAtOnce() throws Exception {
		Map<String, Object> body = nav("reports");
		body.remove("label");
		body.remove("path");
		expectRejected(body, "Invalid request: label must not be blank; path must not be blank.");
	}

	// ---- POST /menus: duplicate key ----

	@Test
	void existingKeyIs409() throws Exception {
		postMenu(nav("dashboard"))
				.andExpect(status().isConflict())
				.andExpect(content().json("{\"error\":{\"code\":\"DUPLICATE_MENU_KEY\","
						+ "\"message\":\"A menu with key 'dashboard' already exists.\"}}", JsonCompareMode.STRICT));

		Integer menus = jdbc.queryForObject("SELECT count(*) FROM menus", Integer.class);
		Integer auditRows = jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class);
		assertThat(menus).isEqualTo(12);
		assertThat(auditRows).isZero();
	}

	// ---- POST /menus: rejected by the parent rules ----

	@Test
	void detailWithoutParentIs400() throws Exception {
		expectRejected(detail("site-detail", null), "A DETAIL menu needs a NAV parent in parentId.");
	}

	@Test
	void navWithParentIs400() throws Exception {
		Map<String, Object> body = nav("reports");
		body.put("parentId", menuId("compliance").toString());
		expectRejected(body, "A NAV menu has no parent; leave parentId empty.");
	}

	@Test
	void unknownParentIs400() throws Exception {
		UUID unknown = UUID.fromString("00000000-0000-0000-0000-000000000001");
		expectRejected(detail("site-detail", unknown),
				"parentId 00000000-0000-0000-0000-000000000001 is not a menu.");
	}

	@Test
	void detailUnderDetailIs400() throws Exception {
		expectRejected(detail("site-detail", menuId("patient-detail")),
				"The parent of a DETAIL menu must be a NAV menu; 'patient-detail' is a DETAIL.");
	}

	// ---- helpers ----

	private ResultActions getMenus() throws Exception {
		return mockMvc.perform(get("/admin/v1/menus").header("Authorization", "Bearer " + tokens.forUser("kc-admin")));
	}

	private ResultActions postMenu(Map<String, Object> body) throws Exception {
		return mockMvc.perform(post("/admin/v1/menus")
				.header("Authorization", "Bearer " + tokens.forUser("kc-admin"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)));
	}

	/** 400 VALIDATION_ERROR with exactly this message, and nothing saved. */
	private void expectRejected(Map<String, Object> body, String message) throws Exception {
		String expected = objectMapper.writeValueAsString(
				Map.of("error", Map.of("code", "VALIDATION_ERROR", "message", message)));
		postMenu(body)
				.andExpect(status().isBadRequest())
				.andExpect(content().json(expected, JsonCompareMode.STRICT));

		Integer menus = jdbc.queryForObject("SELECT count(*) FROM menus", Integer.class);
		Integer auditRows = jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class);
		assertThat(menus).as("the 12 seed menus only").isEqualTo(12);
		assertThat(auditRows).isZero();
	}

	/** A valid NAV body; tests change or remove fields from it. */
	private Map<String, Object> nav(String key) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("key", key);
		body.put("label", "Reports");
		body.put("path", "/" + key);
		body.put("icon", "DocumentIcon");
		body.put("type", "NAV");
		body.put("sortOrder", 110);
		body.put("isActive", true);
		return body;
	}

	/** A valid DETAIL body under the given parent (null leaves parentId out). */
	private Map<String, Object> detail(String key, UUID parentId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("key", key);
		body.put("label", "Site detail");
		body.put("path", "/" + key + "/:id");
		body.put("type", "DETAIL");
		if (parentId != null) {
			body.put("parentId", parentId.toString());
		}
		body.put("sortOrder", 22);
		return body;
	}

	/** The test's transaction never commits, so plain SQL sees Hibernate's queued writes only after a flush. */
	private void sendPendingWrites() {
		entityManager.flush();
	}

	private UUID menuId(String key) {
		return jdbc.queryForObject("SELECT id FROM menus WHERE key = ?", UUID.class, key);
	}

	private void insertNav(String key, int sortOrder) {
		jdbc.update("INSERT INTO menus (key, label, path, type, sort_order) VALUES (?, ?, ?, 'NAV', ?)",
				key, key, "/" + key, sortOrder);
	}
}
