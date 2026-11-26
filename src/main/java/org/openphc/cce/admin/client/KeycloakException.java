package org.openphc.cce.admin.client;

/** Keycloak could not be reached, did not answer in time, or refused the call; the message says why. */
public class KeycloakException extends RuntimeException {

	public KeycloakException(String message, Throwable cause) {
		super(message, cause);
	}
}
