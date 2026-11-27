package org.openphc.cce.admin.client;

/**
 * Keycloak refused a value of the user (its user-profile rules). The reason is Keycloak's, field by field,
 * e.g. "username error-invalid-length", or two fields joined by "; ".
 */
public class KeycloakRejectedException extends KeycloakException {

	public KeycloakRejectedException(String prefix, String reason) {
		super(prefix, reason, null);
	}
}
