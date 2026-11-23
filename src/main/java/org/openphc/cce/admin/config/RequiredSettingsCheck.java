package org.openphc.cce.admin.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Stops startup with a clear message when a required setting has no value.
 * Runs once all settings are read and before any bean (e.g. the database connection) is created.
 * Locally, the local profile fills these in; on servers they come from environment variables.
 */
public class RequiredSettingsCheck implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

	@Override
	public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
		ConfigurableEnvironment environment = event.getEnvironment();

		List<String> missing = new ArrayList<>();
		Map<String, String> required = requiredSettings();
		for (Map.Entry<String, String> setting : required.entrySet()) {
			String springKey = setting.getKey();
			String variableName = setting.getValue();
			if (hasNoValue(environment, springKey)) {
				missing.add(variableName);
			}
		}

		if (!missing.isEmpty()) {
			throw new IllegalStateException("Missing required setting(s): " + String.join(", ", missing)
					+ ". Set them as environment variables, or run with SPRING_PROFILES_ACTIVE=local for local defaults.");
		}
	}

	/** Spring setting → the environment variable that fills it. */
	private Map<String, String> requiredSettings() {
		Map<String, String> required = new LinkedHashMap<>();
		required.put("spring.datasource.url", "DB_URL");
		required.put("spring.datasource.username", "DB_USERNAME");
		required.put("spring.datasource.password", "DB_PASSWORD");
		required.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", "JWT_ISSUER");
		required.put("spring.security.oauth2.resourceserver.jwt.audiences", "JWT_AUDIENCE");
		return required;
	}

	/** Not set, or set to an empty value (what a deploy leaves when a variable is forgotten). */
	private boolean hasNoValue(ConfigurableEnvironment environment, String springKey) {
		String value;
		try {
			value = environment.getProperty(springKey);
		} catch (IllegalArgumentException placeholderNotResolved) {
			return true;
		}
		if (value == null) {
			return true;
		}
		return value.isBlank();
	}
}
