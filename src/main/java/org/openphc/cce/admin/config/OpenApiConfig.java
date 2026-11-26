package org.openphc.cce.admin.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Title and description shown in Swagger UI (/swagger-ui.html) and in /v3/api-docs, and its Authorize button. */
@Configuration
public class OpenApiConfig {

	private static final String BEARER_TOKEN = "bearerAuth";

	@Bean
	public OpenAPI adminServiceOpenApi() {
		Info info = new Info()
				.title("CCE Admin Service API")
				.version("v1")
				.description("Users, roles, menus and API permissions for the CCE consoles. "
						+ "Every endpoint except health needs a Bearer token.");

		// Lets Swagger UI send a token: Authorize → paste the token → every "Try it out" sends it
		SecurityScheme bearerToken = new SecurityScheme()
				.type(SecurityScheme.Type.HTTP)
				.scheme("bearer")
				.bearerFormat("JWT")
				.description("Access token from Keycloak; locally: ./local/get-token.sh");
		Components components = new Components().addSecuritySchemes(BEARER_TOKEN, bearerToken);
		SecurityRequirement everyEndpoint = new SecurityRequirement().addList(BEARER_TOKEN);

		return new OpenAPI().info(info).components(components).addSecurityItem(everyEndpoint);
	}
}
