package org.openphc.cce.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where Keycloak is and how this service logs in to its Admin API (cce.keycloak.* in application.yml). */
@ConfigurationProperties(prefix = "cce.keycloak")
public record KeycloakProperties(String baseUrl, String realm, String clientId, String clientSecret) {
}
