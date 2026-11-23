package org.openphc.cce.admin.config;

import org.openphc.cce.admin.security.JsonAuthEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every request needs a valid token from our Keycloak realm, except health and the API docs.
 * Which role may call which URL is decided by the gateway, not here.
 */
@Configuration
public class SecurityConfig {

	private static final String[] OPEN_PATHS = {
			"/admin/v1/health", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**" };

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, JsonAuthEntryPoint authEntryPoint)
			throws Exception {
		// No sessions or cookies: every request brings its own token, so CSRF protection isn't needed
		http.csrf(csrf -> csrf.disable());
		http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

		http.authorizeHttpRequests(requests -> requests
				.requestMatchers(OPEN_PATHS).permitAll()
				.anyRequest().authenticated());

		// Signature, iss, aud and exp are checked as set in spring.security.oauth2.resourceserver.jwt
		http.oauth2ResourceServer(resourceServer -> resourceServer
				.jwt(Customizer.withDefaults())
				.authenticationEntryPoint(authEntryPoint));
		// Same 401 when there is no token at all
		http.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authEntryPoint));

		return http.build();
	}
}
