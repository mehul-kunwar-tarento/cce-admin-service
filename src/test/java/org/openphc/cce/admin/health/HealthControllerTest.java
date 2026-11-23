package org.openphc.cce.admin.health;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

// The JWT settings must have values for the app to start, even with security filters off
@WebMvcTest(controllers = HealthController.class, properties = { "JWT_ISSUER=http://test-keycloak/realms/cce", "JWT_AUDIENCE=cce-admin-service" })
// Security filters off: this test checks only the health body; security is tested in TokenCheckTest
@AutoConfigureMockMvc(addFilters = false)
class HealthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DatabaseCheck databaseCheck;

	@Test
	void databaseUp() throws Exception {
		when(databaseCheck.isUp()).thenReturn(true);

		mockMvc.perform(get("/admin/v1/health"))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"status\":\"UP\",\"db\":\"UP\"}", JsonCompareMode.STRICT));
	}

	@Test
	void databaseDownIsStill200AndUp() throws Exception {
		when(databaseCheck.isUp()).thenReturn(false);

		mockMvc.perform(get("/admin/v1/health"))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"status\":\"UP\",\"db\":\"DOWN\"}", JsonCompareMode.STRICT));
	}

	@Test
	void notWrappedInEnvelope() throws Exception {
		when(databaseCheck.isUp()).thenReturn(true);

		mockMvc.perform(get("/admin/v1/health"))
				.andExpect(jsonPath("$.data").doesNotExist());
	}
}
