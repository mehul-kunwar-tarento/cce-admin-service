package org.openphc.cce.admin.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.health.DatabaseCheck;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The whole app without a database, so Swagger is checked as it really runs. */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
		+ "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
		+ "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration")
@AutoConfigureMockMvc
class OpenApiTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DatabaseCheck databaseCheck;

	@Test
	void apiDocsHaveOurTitleAndListHealth() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("CCE Admin Service API"))
				.andExpect(jsonPath("$.info.version").value("v1"))
				.andExpect(jsonPath("$.paths['/admin/v1/health'].get").exists());
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
