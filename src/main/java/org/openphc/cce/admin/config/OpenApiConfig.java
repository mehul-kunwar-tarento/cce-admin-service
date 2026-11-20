package org.openphc.cce.admin.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Title and description shown in Swagger UI (/swagger-ui.html) and in /v3/api-docs. */
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI adminServiceOpenApi() {
		Info info = new Info()
				.title("CCE Admin Service API")
				.version("v1")
				.description("Users, roles, menus and API permissions for the CCE consoles. "
						+ "Every endpoint except health needs a Bearer token.");
		return new OpenAPI().info(info);
	}
}
