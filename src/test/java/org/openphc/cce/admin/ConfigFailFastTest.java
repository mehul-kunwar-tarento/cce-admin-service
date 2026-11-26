package org.openphc.cce.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.config.RequiredSettingsCheck;
import org.springframework.boot.SpringApplication;

/**
 * Outside the local profile, a missing DB_URL, JWT, Keycloak or gateway setting stops startup instead of
 * silently pointing at localhost.
 */
class ConfigFailFastTest {

	private static final String KEYCLOAK_BASE_URL = "--KEYCLOAK_BASE_URL=http://kc";
	private static final String KEYCLOAK_CLIENT_SECRET = "--KEYCLOAK_CLIENT_SECRET=s";
	private static final String GATEWAY_BASE_URL = "--GATEWAY_BASE_URL=http://gw";

	@Test
	void startupWithNothingSetNamesAllEightVariables() {
		SpringApplication app = appWithCheck();

		// port 0 = any free port, so the test never clashes with a running service
		assertThatThrownBy(() -> app.run("--server.port=0"))
				.hasMessageContaining("Missing required setting(s): DB_URL, DB_USERNAME, DB_PASSWORD, JWT_ISSUER,"
						+ " JWT_AUDIENCE, KEYCLOAK_BASE_URL, KEYCLOAK_CLIENT_SECRET, GATEWAY_BASE_URL.");
	}

	@Test
	void emptyDbUrlCountsAsMissing() {
		SpringApplication app = appWithCheck();

		// DB_URL set but empty, as a deploy leaves it when the value was forgotten
		assertThatThrownBy(() -> app.run("--server.port=0", "--DB_URL=", "--DB_USERNAME=u", "--DB_PASSWORD=p",
				"--JWT_ISSUER=http://kc/realms/cce", "--JWT_AUDIENCE=cce-admin-service", KEYCLOAK_BASE_URL,
				KEYCLOAK_CLIENT_SECRET, GATEWAY_BASE_URL))
				.hasMessageContaining("Missing required setting(s): DB_URL.");
	}

	@Test
	void missingJwtIssuerIsNamed() {
		SpringApplication app = appWithCheck();

		assertThatThrownBy(() -> app.run("--server.port=0", "--DB_URL=jdbc:postgresql://db/x", "--DB_USERNAME=u",
				"--DB_PASSWORD=p", "--JWT_AUDIENCE=cce-admin-service", KEYCLOAK_BASE_URL, KEYCLOAK_CLIENT_SECRET, GATEWAY_BASE_URL))
				.hasMessageContaining("Missing required setting(s): JWT_ISSUER.");
	}

	@Test
	void missingJwtAudienceIsNamed() {
		SpringApplication app = appWithCheck();

		assertThatThrownBy(() -> app.run("--server.port=0", "--DB_URL=jdbc:postgresql://db/x", "--DB_USERNAME=u",
				"--DB_PASSWORD=p", "--JWT_ISSUER=http://kc/realms/cce", KEYCLOAK_BASE_URL, KEYCLOAK_CLIENT_SECRET, GATEWAY_BASE_URL))
				.hasMessageContaining("Missing required setting(s): JWT_AUDIENCE.");
	}

	@Test
	void missingKeycloakBaseUrlIsNamed() {
		SpringApplication app = appWithCheck();

		assertThatThrownBy(() -> app.run("--server.port=0", "--DB_URL=jdbc:postgresql://db/x", "--DB_USERNAME=u",
				"--DB_PASSWORD=p", "--JWT_ISSUER=http://kc/realms/cce", "--JWT_AUDIENCE=cce-admin-service",
				KEYCLOAK_CLIENT_SECRET, GATEWAY_BASE_URL))
				.hasMessageContaining("Missing required setting(s): KEYCLOAK_BASE_URL.");
	}

	@Test
	void missingKeycloakClientSecretIsNamed() {
		SpringApplication app = appWithCheck();

		assertThatThrownBy(() -> app.run("--server.port=0", "--DB_URL=jdbc:postgresql://db/x", "--DB_USERNAME=u",
				"--DB_PASSWORD=p", "--JWT_ISSUER=http://kc/realms/cce", "--JWT_AUDIENCE=cce-admin-service",
				KEYCLOAK_BASE_URL, GATEWAY_BASE_URL))
				.hasMessageContaining("Missing required setting(s): KEYCLOAK_CLIENT_SECRET.");
	}

	@Test
	void missingGatewayBaseUrlIsNamed() {
		SpringApplication app = appWithCheck();

		assertThatThrownBy(() -> app.run("--server.port=0", "--DB_URL=jdbc:postgresql://db/x", "--DB_USERNAME=u",
				"--DB_PASSWORD=p", "--JWT_ISSUER=http://kc/realms/cce", "--JWT_AUDIENCE=cce-admin-service",
				KEYCLOAK_BASE_URL, KEYCLOAK_CLIENT_SECRET))
				.hasMessageContaining("Missing required setting(s): GATEWAY_BASE_URL.");
	}

	private SpringApplication appWithCheck() {
		SpringApplication app = new SpringApplication(CceAdminServiceApplication.class);
		app.addListeners(new RequiredSettingsCheck());
		return app;
	}
}
