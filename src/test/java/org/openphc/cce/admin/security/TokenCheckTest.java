package org.openphc.cce.admin.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole app with security on. Tokens are signed by {@link TestTokens}, whose public key the app
 * fetches from a JWKS URL just as it would from Keycloak.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TokenCheckTest {

	private static final String UNAUTHENTICATED_BODY =
			"{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"Missing or invalid token.\"}}";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

	static TestTokens tokens = new TestTokens();

	@DynamicPropertySource
	static void jwtSettings(DynamicPropertyRegistry registry) {
		registry.add("JWT_ISSUER", () -> TestTokens.ISSUER);
		registry.add("JWT_AUDIENCE", () -> TestTokens.AUDIENCE);
		registry.add("JWT_JWK_SET_URI", tokens::jwkSetUri);
	}

	@AfterAll
	static void stopJwksServer() {
		tokens.stop();
	}

	@Autowired
	private MockMvc mockMvc;

	// ---- 401 ----

	@Test
	void noTokenIs401() throws Exception {
		expect401(mockMvc.perform(get("/test/secure")));
	}

	@Test
	void tokenThatIsNotAJwtIs401() throws Exception {
		expect401(callWithToken("not-a-token"));
	}

	@Test
	void badSignatureIs401() throws Exception {
		expect401(callWithToken(tokens.signedByAnotherKey()));
	}

	@Test
	void expiredTokenIs401() throws Exception {
		expect401(callWithToken(tokens.expired()));
	}

	@Test
	void wrongIssuerIs401() throws Exception {
		expect401(callWithToken(tokens.withIssuer("http://other-keycloak/realms/cce")));
	}

	@Test
	void tokenForAnotherServiceIs401() throws Exception {
		expect401(callWithToken(tokens.withAudience("gateway-service")));
	}

	@Test
	void unknownUrlWithoutTokenIs401() throws Exception {
		// Security answers before the URL is looked up, so strangers can't tell which URLs exist
		expect401(mockMvc.perform(get("/admin/v1/rolez")));
	}

	// ---- past the token check ----

	@Test
	void validTokenPassesTheTokenCheck() throws Exception {
		// 403, not 401: the token got past the token check; nobody is in users, so the local-user check says no
		callWithToken(tokens.valid())
				.andExpect(status().isForbidden());
	}

	@Test
	void healthNeedsNoToken() throws Exception {
		mockMvc.perform(get("/admin/v1/health"))
				.andExpect(status().isOk());
	}

	// ---- helpers ----

	private ResultActions callWithToken(String token) throws Exception {
		return mockMvc.perform(get("/test/secure").header("Authorization", "Bearer " + token));
	}

	/** 401 in our shape, with the fixed message and nothing else in the body. */
	private void expect401(ResultActions result) throws Exception {
		result.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"))
				.andExpect(content().json(UNAUTHENTICATED_BODY, JsonCompareMode.STRICT));
	}
}
