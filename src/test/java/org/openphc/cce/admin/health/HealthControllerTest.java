package org.openphc.cce.admin.health;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
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
				.andExpect(content().json("{\"status\":\"UP\",\"db\":\"UP\"}", true));
	}

	@Test
	void databaseDownIsStill200AndUp() throws Exception {
		when(databaseCheck.isUp()).thenReturn(false);

		mockMvc.perform(get("/admin/v1/health"))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"status\":\"UP\",\"db\":\"DOWN\"}", true));
	}

	@Test
	void notWrappedInEnvelope() throws Exception {
		when(databaseCheck.isUp()).thenReturn(true);

		mockMvc.perform(get("/admin/v1/health"))
				.andExpect(jsonPath("$.data").doesNotExist());
	}
}
