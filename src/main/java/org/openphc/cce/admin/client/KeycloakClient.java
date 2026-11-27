package org.openphc.cce.admin.client;

import java.util.List;
import java.util.Optional;

/** What this service asks of Keycloak's Admin API. Every method throws KeycloakException when Keycloak fails. */
public interface KeycloakClient {

	/** The user with exactly this username; empty if there is none. */
	Optional<KeycloakUser> findUserByUsername(String username);

	/** Whether the user has this realm role directly, through a composite role or through a group. */
	boolean hasRealmRole(String keycloakId, String roleName);

	/**
	 * Creates an enabled account with no password; Keycloak makes the user set one at first login. Returns the
	 * new account's id. Throws KeycloakConflictException when Keycloak already has the username or email, and
	 * KeycloakRejectedException when it refuses a value (e.g. a name with '<').
	 */
	String createUser(String username, String email, String firstName, String lastName);

	/** Gives the user these realm roles in one call. Throws KeycloakException naming any role Keycloak lacks. */
	void assignRealmRoles(String keycloakId, List<String> roleNames);

	/** Deletes the account. One that is already gone counts as deleted. */
	void deleteUser(String keycloakId);

	/**
	 * Changes the account's email and names; everything else (enabled, required actions, roles) stays. Throws
	 * KeycloakConflictException when another Keycloak account has the email, KeycloakRejectedException when
	 * Keycloak refuses a value.
	 */
	void updateUser(String keycloakId, String email, String firstName, String lastName);
}
