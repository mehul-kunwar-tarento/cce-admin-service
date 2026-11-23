package org.openphc.cce.admin.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

// The JWT settings must have values for the app to start, even with security filters off
@WebMvcTest(controllers = EnvelopeTestController.class, properties = { "JWT_ISSUER=http://test-keycloak/realms/cce", "JWT_AUDIENCE=cce-admin-service" })
// Security filters off: this test checks only the response envelope and paging; security is tested in TokenCheckTest
@AutoConfigureMockMvc(addFilters = false)
class EnvelopeTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void singleItemHasDataAndNoPaginationKey() throws Exception {
		mockMvc.perform(get("/test/item"))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"data\":{\"name\":\"one\"}}", JsonCompareMode.STRICT));
	}

	@Test
	void listHasPaginationWithDefaults() throws Exception {
		mockMvc.perform(get("/test/items"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].name").value("first on page 1"))
				.andExpect(jsonPath("$.pagination.page").value(1))
				.andExpect(jsonPath("$.pagination.pageSize").value(20))
				.andExpect(jsonPath("$.pagination.total").value(42));
	}

	@Test
	void pageSize100IsAllowed() throws Exception {
		mockMvc.perform(get("/test/items").param("page", "3").param("pageSize", "100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pagination.page").value(3))
				.andExpect(jsonPath("$.pagination.pageSize").value(100));
	}

	@Test
	void pageSize101IsRejected() throws Exception {
		mockMvc.perform(get("/test/items").param("pageSize", "101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.message").value("Invalid request: pageSize must be less than or equal to 100."));
	}

	@Test
	void pageZeroIsRejected() throws Exception {
		mockMvc.perform(get("/test/items").param("page", "0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.message").value("Invalid request: page must be greater than or equal to 1."));
	}

	@Test
	void pageThatIsNotANumberIsRejectedWithoutJavaNames() throws Exception {
		mockMvc.perform(get("/test/items").param("page", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.message").value("Invalid request: page has an invalid value."))
				.andExpect(content().string(not(containsString("java."))));
	}
}
