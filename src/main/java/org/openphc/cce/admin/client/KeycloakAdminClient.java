package org.openphc.cce.admin.client;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.openphc.cce.admin.config.KeycloakProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.client.ClientBuilder;

/** KeycloakClient on Keycloak's own Java library, logged in as this service's service account. */
@Component
public class KeycloakAdminClient implements KeycloakClient {

	// Longest wait for Keycloak to connect, and again to answer, before it counts as unreachable
	static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final Keycloak keycloak;
	private final String realm;
	private final String baseUrl;

	// Two constructors, so Spring is told which one to use
	@Autowired
	public KeycloakAdminClient(KeycloakProperties properties) {
		this(properties, TIMEOUT);
	}

	/** Tests pass a shorter timeout. */
	KeycloakAdminClient(KeycloakProperties properties, Duration timeout) {
		this.realm = properties.realm();
		this.baseUrl = properties.baseUrl();
		// Building does not connect; the first call fetches a token, then makes the request
		this.keycloak = KeycloakBuilder.builder()
				.serverUrl(properties.baseUrl())
				.realm(properties.realm())
				// This service's own login (no person): client id + secret
				.grantType(OAuth2Constants.CLIENT_CREDENTIALS)
				.clientId(properties.clientId())
				.clientSecret(properties.clientSecret())
				.resteasyClient(httpClient(timeout))
				.build();
	}

	@Override
	public Optional<KeycloakUser> findUserByUsername(String username) {
		List<UserRepresentation> found;
		try {
			// true = exact match; without it Keycloak also returns e.g. superadmin2 for superadmin
			found = keycloak.realm(realm).users().searchByUsername(username, true);
		} catch (RuntimeException e) {
			throw failure("find user " + username, e);
		}
		if (found.isEmpty()) {
			return Optional.empty();
		}
		UserRepresentation user = found.get(0);
		return Optional.of(new KeycloakUser(user.getId(), user.getUsername(), user.getEmail(), user.getFirstName(),
				user.getLastName()));
	}

	@Override
	public boolean hasRealmRole(String keycloakId, String roleName) {
		List<RoleRepresentation> roles;
		try {
			// Effective = given directly, through a composite role or through a group
			roles = keycloak.realm(realm).users().get(keycloakId).roles().realmLevel().listEffective();
		} catch (RuntimeException e) {
			throw failure("read the realm roles of user " + keycloakId, e);
		}
		for (RoleRepresentation role : roles) {
			if (roleName.equals(role.getName())) {
				return true;
			}
		}
		return false;
	}

	private KeycloakException failure(String step, RuntimeException e) {
		return new KeycloakException("Keycloak at " + baseUrl + ": could not " + step + ": " + rootMessage(e), e);
	}

	/** The innermost cause says what really happened, e.g. "Connection refused" or "Read timed out". */
	private String rootMessage(Throwable e) {
		Throwable root = e;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		if (root.getMessage() == null) {
			return root.getClass().getSimpleName();
		}
		return root.getMessage();
	}

	private ResteasyClient httpClient(Duration timeout) {
		// Keycloak 26.5 sends fields the 26.0 library does not know; skip them instead of failing to read
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		ResteasyJackson2Provider jsonReader = new ResteasyJackson2Provider();
		jsonReader.setMapper(objectMapper);

		ResteasyClientBuilder builder = (ResteasyClientBuilder) ClientBuilder.newBuilder();
		builder.connectTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
		builder.readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
		builder.register(jsonReader);
		return builder.build();
	}
}
