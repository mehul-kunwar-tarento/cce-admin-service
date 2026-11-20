package org.openphc.cce.admin.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openphc.cce.admin.health.DatabaseCheck;
import org.openphc.cce.admin.health.HealthController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;

@WebMvcTest(controllers = { ErrorTestController.class, HealthController.class })
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DatabaseCheck databaseCheck;

	@Test
	void unknownUrlIs404NotFound() throws Exception {
		mockMvc.perform(get("/admin/v1/rolez"))
				.andExpect(status().isNotFound())
				.andExpect(error("NOT_FOUND", "No endpoint GET /admin/v1/rolez."))
				.andExpect(noSpringDefaultKeys());
	}

	@Test
	void wrongMethodIs405() throws Exception {
		mockMvc.perform(delete("/admin/v1/health"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(error("METHOD_NOT_ALLOWED", "Method DELETE is not allowed for this URL."))
				.andExpect(noSpringDefaultKeys());
	}

	@Test
	void bodyThatIsNotJsonIs415() throws Exception {
		mockMvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("hello"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(error("UNSUPPORTED_MEDIA_TYPE",
						"Content-Type text/plain is not supported. Send JSON (Content-Type: application/json)."))
				.andExpect(noSpringDefaultKeys());
	}

	@Test
	void bodyWithoutContentTypeIs415() throws Exception {
		// Spring treats a missing Content-Type as application/octet-stream
		mockMvc.perform(post("/test/body").content("{\"name\":\"a\"}"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(error("UNSUPPORTED_MEDIA_TYPE",
						"Content-Type application/octet-stream is not supported. Send JSON (Content-Type: application/json)."))
				.andExpect(noSpringDefaultKeys());
	}

	@Test
	void brokenJsonIs400() throws Exception {
		mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
				.andExpect(status().isBadRequest())
				.andExpect(error("VALIDATION_ERROR", "Request body is not valid JSON, or a field has the wrong type."))
				.andExpect(noSpringDefaultKeys());
	}

	@Test
	void blankFieldIs400NamingTheField() throws Exception {
		mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(error("VALIDATION_ERROR", "Invalid request: name must not be blank."))
				.andExpect(noSpringDefaultKeys());
	}

	@Test
	void apiExceptionKeepsItsStatusCodeAndMessage() throws Exception {
		mockMvc.perform(get("/test/api-exception"))
				.andExpect(status().isNotFound())
				.andExpect(error("ROLE_NOT_FOUND", "x"))
				.andExpect(noSpringDefaultKeys());
	}

	@Test
	void unexpectedErrorIs500WithReferenceAndHidesTheDetail(CapturedOutput output) throws Exception {
		MvcResult result = mockMvc.perform(get("/test/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
				.andExpect(noSpringDefaultKeys())
				.andReturn();

		String message = JsonPath.read(result.getResponse().getContentAsString(), "$.error.message");
		assertThat(message).doesNotContain("secret detail");
		assertThat(message).doesNotContain("RuntimeException");

		String reference = referenceIn(message);
		assertThat(reference).hasSize(8);

		// The same reference is on the ERROR log line, with the real exception and its stack trace
		assertThat(output.getOut()).contains("ERROR");
		assertThat(output.getOut()).contains("[ref=" + reference + "] GET /test/boom failed");
		assertThat(output.getOut()).contains("java.lang.RuntimeException: secret detail");
		assertThat(output.getOut()).contains("at org.openphc.cce.admin.error.ErrorTestController.boom");
	}

	@Test
	void otherSpringClientErrorKeepsItsStatusAndIsNotLoggedAsError(CapturedOutput output) throws Exception {
		mockMvc.perform(get("/admin/v1/health").accept(MediaType.APPLICATION_XML))
				.andExpect(status().isNotAcceptable());

		assertThat(output.getOut()).doesNotContain("[ref=");
	}

	private ResultMatcher error(String code, String message) {
		return result -> {
			jsonPath("$.error.code").value(code).match(result);
			jsonPath("$.error.message").value(message).match(result);
		};
	}

	/** Spring's default error body has these keys; ours never does. */
	private ResultMatcher noSpringDefaultKeys() {
		return result -> {
			jsonPath("$.timestamp").doesNotExist().match(result);
			jsonPath("$.status").doesNotExist().match(result);
			jsonPath("$.path").doesNotExist().match(result);
		};
	}

	/** The 8 characters after "report reference " in a 500 message. */
	private String referenceIn(String message) {
		String marker = "report reference ";
		int start = message.indexOf(marker) + marker.length();
		return message.substring(start, start + 8);
	}
}
