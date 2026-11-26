package org.openphc.cce.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where the gateway is (cce.gateway.* in application.yml). */
@ConfigurationProperties(prefix = "cce.gateway")
public record GatewayProperties(String baseUrl) {
}
