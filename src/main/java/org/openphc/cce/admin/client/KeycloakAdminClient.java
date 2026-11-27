package org.openphc.cce.admin.client;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.JacksonProvider;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.openphc.cce.admin.config.KeycloakProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;

/** KeycloakClient on Keycloak's own Java library, logged in as this service's service account. */
@Component
public class KeycloakAdminClient implements KeycloakClient {

	// Longest wait for Keycloak to connect, and again to answer, before it counts as unreachable
	static final Duration TIMEOUT = Duration.ofSeconds(10);

	// Keycloak's name for "the user must set a new password at the next login"
	private static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

	private final Keycloak keycloak;
	// Reads Keycloak's error bodies only; requests and answers go through Keycloak's own JSON setup
	private final ObjectMapper objectMapper = new ObjectMapper();
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

	@Override
	public String createUser(String username, String email, String firstName, String lastName) {
		UserRepresentation user = new UserRepresentation();
		user.setUsername(username);
		user.setEmail(email);
		user.setFirstName(firstName);
		user.setLastName(lastName);
		user.setEnabled(true);
		// No password is ever sent: at the first login Keycloak makes the user set one
		user.setRequiredActions(List.of(UPDATE_PASSWORD));

		String step = "create user " + username;
		Response response;
		try {
			response = keycloak.realm(realm).users().create(user);
		} catch (RuntimeException e) {
			throw failure(step, e);
		}
		// create() hands back every status without throwing, so the answer is read here; closing frees the connection
		try (response) {
			int status = response.getStatus();
			if (status == 201) {
				// The new id is the last part of the Location header: …/users/<id>
				return CreatedResponseUtil.getCreatedId(response);
			}
			throw answerFailure(step, response);
		}
	}

	@Override
	public void updateUser(String keycloakId, String email, String firstName, String lastName) {
		// Only these three are sent; Keycloak keeps every field that is left out
		UserRepresentation changes = new UserRepresentation();
		changes.setEmail(email);
		changes.setFirstName(firstName);
		changes.setLastName(lastName);

		String step = "update user " + keycloakId;
		try {
			keycloak.realm(realm).users().get(keycloakId).update(changes);
		} catch (WebApplicationException e) {
			// Keycloak answered, with an error status; unlike create(), update() throws instead of returning it
			throw answerFailure(step, e.getResponse());
		} catch (RuntimeException e) {
			throw failure(step, e);
		}
	}

	/** Keycloak's error answer as an exception: 409 conflict, 400 refused value, anything else a failure. */
	private KeycloakException answerFailure(String step, Response response) {
		int status = response.getStatus();
		JsonNode body = readJson(response);
		if (status == 409) {
			return new KeycloakConflictException(prefix(step), errorMessage(body, status));
		}
		if (status == 400) {
			return new KeycloakRejectedException(prefix(step), fieldErrors(body));
		}
		return new KeycloakException(prefix(step), errorMessage(body, status), null);
	}

	@Override
	public void assignRealmRoles(String keycloakId, List<String> roleNames) {
		String step = "assign realm roles " + String.join(", ", roleNames) + " to user " + keycloakId;
		RoleScopeResource userRealmRoles = keycloak.realm(realm).users().get(keycloakId).roles().realmLevel();

		List<RoleRepresentation> available;
		try {
			// This account may not read a role by its name, but it may list the roles this user can still get
			available = userRealmRoles.listAvailable();
		} catch (RuntimeException e) {
			throw failure(step, e);
		}

		List<RoleRepresentation> toAdd = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (String name : roleNames) {
			RoleRepresentation role = findByName(available, name);
			if (role == null) {
				missing.add(name);
			} else {
				toAdd.add(role);
			}
		}
		if (!missing.isEmpty()) {
			throw new KeycloakException(prefix(step), "Keycloak has no realm role " + String.join(", ", missing), null);
		}

		try {
			userRealmRoles.add(toAdd);
		} catch (RuntimeException e) {
			throw failure(step, e);
		}
	}

	@Override
	public void deleteUser(String keycloakId) {
		String step = "delete user " + keycloakId;
		Response response;
		try {
			response = keycloak.realm(realm).users().delete(keycloakId);
		} catch (RuntimeException e) {
			throw failure(step, e);
		}
		// delete() hands back every status without throwing, like create()
		try (response) {
			int status = response.getStatus();
			if (status == 204) {
				return;
			}
			if (status == 404) {
				// Already gone, which is what deleting it is for
				return;
			}
			throw new KeycloakException(prefix(step), errorMessage(readJson(response), status), null);
		}
	}

	private RoleRepresentation findByName(List<RoleRepresentation> roles, String name) {
		for (RoleRepresentation role : roles) {
			if (name.equals(role.getName())) {
				return role;
			}
		}
		return null;
	}

	/** Keycloak's 400 comes as one {field, errorMessage} or as {errors: [ … ]}; each becomes "field errorMessage". */
	private String fieldErrors(JsonNode body) {
		List<String> parts = new ArrayList<>();
		if (body != null && body.has("errors")) {
			for (JsonNode error : body.get("errors")) {
				parts.add(fieldError(error));
			}
		} else if (body != null && body.has("field")) {
			parts.add(fieldError(body));
		}
		if (parts.isEmpty()) {
			return errorMessage(body, 400);
		}
		return String.join("; ", parts);
	}

	private String fieldError(JsonNode error) {
		return error.path("field").asText() + " " + error.path("errorMessage").asText();
	}

	/**
	 * Keycloak's own text, from "errorMessage" (e.g. "User exists with same username") or "error" (e.g. "Invalid
	 * json representation …"); just the status when there is neither.
	 */
	private String errorMessage(JsonNode body, int status) {
		String message = textField(body, "errorMessage");
		if (message != null) {
			return message;
		}
		message = textField(body, "error");
		if (message != null) {
			return message;
		}
		return "HTTP " + status;
	}

	/** The field's text; null when the body or the field is missing, not text, or blank. */
	private String textField(JsonNode body, String name) {
		if (body == null) {
			return null;
		}
		JsonNode field = body.get(name);
		if (field == null || !field.isTextual() || field.asText().isBlank()) {
			return null;
		}
		return field.asText();
	}

	/** Null when the body is empty or not JSON. */
	private JsonNode readJson(Response response) {
		if (!response.hasEntity()) {
			return null;
		}
		String text = response.readEntity(String.class);
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readTree(text);
		} catch (IOException notJson) {
			return null;
		}
	}

	private String prefix(String step) {
		return "Keycloak at " + baseUrl + ": could not " + step + ": ";
	}

	private KeycloakException failure(String step, RuntimeException e) {
		return new KeycloakException(prefix(step), rootMessage(e), e);
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
		ResteasyClientBuilder builder = (ResteasyClientBuilder) ClientBuilder.newBuilder();
		builder.connectTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
		builder.readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
		// Keycloak's own JSON setup: skips fields it does not know when reading (Keycloak 26.5 sends some the
		// 26.0 library lacks) and leaves empty fields out when writing (26.5 refuses some the library has)
		builder.register(new JacksonProvider());
		return builder.build();
	}
}
