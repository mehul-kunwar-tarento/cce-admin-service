package org.openphc.cce.admin.client;

/** The parts of a Keycloak user this service uses. */
public record KeycloakUser(String id, String username, String email, String firstName, String lastName) {
}
