package org.openphc.cce.admin.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.security.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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

	// ---- helpers ----

	private ResultActions getMenus() throws Exception {
		return mockMvc.perform(get("/admin/v1/menus").header("Authorization", "Bearer " + tokens.forUser("kc-admin")));
	}

	private void insertNav(String key, int sortOrder) {
		jdbc.update("INSERT INTO menus (key, label, path, type, sort_order) VALUES (?, ?, ?, 'NAV', ?)",
				key, key, "/" + key, sortOrder);
	}
}
