package org.openphc.cce.admin.client;

/** Keycloak already has an account with this username or email. */
public class KeycloakConflictException extends KeycloakException {

	public KeycloakConflictException(String prefix, String reason) {
		super(prefix, reason, null);
	}
}
