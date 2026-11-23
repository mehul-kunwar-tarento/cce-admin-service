package org.openphc.cce.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.config.RequiredSettingsCheck;
import org.springframework.boot.SpringApplication;

/**
 * Outside the local profile, a missing DB_URL or JWT setting stops startup instead of
 * silently pointing at localhost.
 */
class ConfigFailFastTest {

	@Test
	void startupWithNothingSetNamesAllFiveVariables() {
		SpringApplication app = appWithCheck();

		// port 0 = any free port, so the test never clashes with a running service
		assertThatThrownBy(() -> app.run("--server.port=0"))
				.hasMessageContaining(
						"Missing required setting(s): DB_URL, DB_USERNAME, DB_PASSWORD, JWT_ISSUER, JWT_AUDIENCE.");
	}

	@Test
	void emptyDbUrlCountsAsMissing() {
		SpringApplication app = appWithCheck();

		// DB_URL set but empty, as a deploy leaves it when the value was forgotten
		assertThatThrownBy(() -> app.run("--server.port=0", "--DB_URL=", "--DB_USERNAME=u", "--DB_PASSWORD=p",
				"--JWT_ISSUER=http://kc/realms/cce", "--JWT_AUDIENCE=cce-admin-service"))
				.hasMessageContaining("Missing required setting(s): DB_URL.");
	}

	@Test
	void missingJwtIssuerIsNamed() {
		SpringApplication app = appWithCheck();

		assertThatThrownBy(() -> app.run("--server.port=0", "--DB_URL=jdbc:postgresql://db/x", "--DB_USERNAME=u",
				"--DB_PASSWORD=p", "--JWT_AUDIENCE=cce-admin-service"))
				.hasMessageContaining("Missing required setting(s): JWT_ISSUER.");
	}

	@Test
	void missingJwtAudienceIsNamed() {
		SpringApplication app = appWithCheck();

		assertThatThrownBy(() -> app.run("--server.port=0", "--DB_URL=jdbc:postgresql://db/x", "--DB_USERNAME=u",
				"--DB_PASSWORD=p", "--JWT_ISSUER=http://kc/realms/cce"))
				.hasMessageContaining("Missing required setting(s): JWT_AUDIENCE.");
	}

	private SpringApplication appWithCheck() {
		SpringApplication app = new SpringApplication(CceAdminServiceApplication.class);
		app.addListeners(new RequiredSettingsCheck());
		return app;
	}
}
