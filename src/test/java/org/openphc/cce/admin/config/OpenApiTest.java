package org.openphc.cce.admin.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.health.DatabaseCheck;
import org.openphc.cce.admin.repository.AuditLogRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole app without a database, so Swagger is checked as it really runs, security included:
 * the docs open without a token.
 */
@SpringBootTest(properties = {
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
		"JWT_ISSUER=http://test-keycloak/realms/cce",
		"JWT_AUDIENCE=cce-admin-service",
		"KEYCLOAK_BASE_URL=http://test-keycloak", "KEYCLOAK_CLIENT_SECRET=test-secret",
		"GATEWAY_BASE_URL=http://test-gateway" })
@AutoConfigureMockMvc
class OpenApiTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DatabaseCheck databaseCheck;

	// No database here, so the repositories get stand-ins; the docs are open and never use them
	@MockitoBean
	private UserRepository userRepository;

	@MockitoBean
	private AuditLogRepository auditLogRepository;

	@MockitoBean
	private PlatformTransactionManager transactionManager;

	@Test
	void apiDocsHaveOurTitleAndListHealth() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("CCE Admin Service API"))
				.andExpect(jsonPath("$.info.version").value("v1"))
				.andExpect(jsonPath("$.paths['/admin/v1/health'].get").exists());
	}

	@Test
	void apiDocsDeclareABearerToken() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
				.andExpect(jsonPath("$.security[0].bearerAuth").isArray());
	}

	@Test
	void healthNeedsNoTokenInTheDocs() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/admin/v1/health'].get.security").isEmpty());
	}

	@Test
	void swaggerUiRedirectsToItsIndexPage() throws Exception {
		mockMvc.perform(get("/swagger-ui.html"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("/swagger-ui/index.html"));
	}

	@Test
	void swaggerUiIndexPageLoads() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk());
	}
}
