package org.openphc.cce.admin.config;

import org.openphc.cce.admin.error.ErrorResponseWriter;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.security.ActiveUserFilter;
import org.openphc.cce.admin.security.JsonAuthEntryPoint;
import org.openphc.cce.admin.security.RolesClaimReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Every request needs a valid token from our Keycloak realm, except health and the API docs, and the
 * token must belong to a local ACTIVE user. Which role may call which URL is decided by the gateway, not here.
 */
@Configuration
public class SecurityConfig {

	/** Open without a token. Public so the local-user check can skip the same paths. */
	public static final String[] OPEN_PATHS = {
			"/admin/v1/health", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**" };

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, JsonAuthEntryPoint authEntryPoint,
			UserRepository userRepository, RolesClaimReader rolesClaimReader,
			ErrorResponseWriter errorResponseWriter,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
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

		// The local-user check runs right after the token check. Created here, not as a bean,
		// so it runs only inside this chain, once per request.
		ActiveUserFilter activeUserFilter = new ActiveUserFilter(
				userRepository, rolesClaimReader, errorResponseWriter, exceptionResolver);
		http.addFilterAfter(activeUserFilter, BearerTokenAuthenticationFilter.class);

		return http.build();
	}
}
