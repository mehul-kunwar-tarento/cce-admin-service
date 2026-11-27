package org.openphc.cce.admin.client;

/** Keycloak could not be reached, did not answer in time, or refused the call; the message says why. */
public class KeycloakException extends RuntimeException {

	private final String reason;

	/**
	 * The message is the prefix (where and which step) followed by the reason, e.g. "Keycloak at
	 * http://kc: could not create user alice: " + "Connection refused".
	 */
	public KeycloakException(String prefix, String reason, Throwable cause) {
		super(prefix + reason, cause);
		this.reason = reason;
	}

	/** Only what went wrong, without the URL and step, for a message to the caller. */
	public String getReason() {
		return reason;
	}
}
