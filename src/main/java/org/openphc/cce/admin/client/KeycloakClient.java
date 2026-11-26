package org.openphc.cce.admin.client;

import java.util.Optional;

/** What this service asks of Keycloak's Admin API. Every method throws KeycloakException when Keycloak fails. */
public interface KeycloakClient {

	/** The user with exactly this username; empty if there is none. */
	Optional<KeycloakUser> findUserByUsername(String username);

	/** Whether the user has this realm role directly, through a composite role or through a group. */
	boolean hasRealmRole(String keycloakId, String roleName);
}
