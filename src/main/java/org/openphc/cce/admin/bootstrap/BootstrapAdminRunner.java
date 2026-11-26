package org.openphc.cce.admin.bootstrap;

import java.util.Locale;
import java.util.Optional;

import org.openphc.cce.admin.client.KeycloakClient;
import org.openphc.cce.admin.client.KeycloakException;
import org.openphc.cce.admin.client.KeycloakUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * On every start, links the Keycloak user named in BOOTSTRAP_ADMIN_USERNAME as a local ACTIVE SUPER_ADMIN, so
 * the first admin can call the API. Runs after the migrations. Never stops the service: a missing admin is
 * logged as an ERROR, and the fix is to set the user up in Keycloak and restart.
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

	private final KeycloakClient keycloakClient;
	private final BootstrapAdminService service;
	private final String username;

	public BootstrapAdminRunner(KeycloakClient keycloakClient, BootstrapAdminService service,
			@Value("${cce.bootstrap.admin-username}") String configuredUsername) {
		this.keycloakClient = keycloakClient;
		this.service = service;
		// Keycloak keeps usernames in lowercase; doing the same keeps "already linked" true on every later start
		this.username = configuredUsername.trim().toLowerCase(Locale.ROOT);
	}

	@Override
	public void run(ApplicationArguments args) {
		if (username.isEmpty()) {
			return;
		}
		// One missing admin must not take the service down: every failure is logged and startup goes on
		try {
			linkIfNeeded();
		} catch (RuntimeException e) {
			log.error("Bootstrap admin {} not linked: {}", username, e.getMessage(), e);
		}
	}

	private void linkIfNeeded() {
		if (service.isLinked(username)) {
			return;
		}

		Optional<KeycloakUser> found = findInKeycloak();
		if (found.isEmpty()) {
			return;
		}
		KeycloakUser keycloakUser = found.get();

		if (!keycloakClient.hasRealmRole(keycloakUser.id(), BootstrapAdminService.SUPER_ADMIN)) {
			log.error("Bootstrap admin {} not linked: the Keycloak user has no realm role {}; give it the role,"
					+ " then restart", username, BootstrapAdminService.SUPER_ADMIN);
			return;
		}
		if (hasNoEmail(keycloakUser)) {
			log.error("Bootstrap admin {} not linked: {} has no email in Keycloak; add one, then restart", username,
					username);
			return;
		}

		service.link(keycloakUser);
		log.info("bootstrap admin {} linked", username);
	}

	/** Empty when the user is not there or Keycloak can't be reached; both are logged here. */
	private Optional<KeycloakUser> findInKeycloak() {
		Optional<KeycloakUser> found;
		try {
			found = keycloakClient.findUserByUsername(username);
		} catch (KeycloakException e) {
			log.error("Bootstrap admin {} not linked: {}; check that Keycloak is running, or create {} in Keycloak"
					+ " first, then restart", username, e.getMessage(), username);
			return Optional.empty();
		}
		if (found.isEmpty()) {
			log.error("Bootstrap admin {} not linked: no such user in Keycloak; create {} in Keycloak first, then"
					+ " restart", username, username);
		}
		return found;
	}

	// The users table needs an email
	private boolean hasNoEmail(KeycloakUser keycloakUser) {
		if (keycloakUser.email() == null) {
			return true;
		}
		return keycloakUser.email().isBlank();
	}
}
