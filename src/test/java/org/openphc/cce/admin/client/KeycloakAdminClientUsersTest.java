package org.openphc.cce.admin.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openphc.cce.admin.config.KeycloakProperties;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/**
 * The user calls of the client against WireMock playing Keycloak, token endpoint included. The answers are
 * the ones the local Keycloak 26.5 gave for the same requests.
 */
class KeycloakAdminClientUsersTest {

	private static final String TOKEN_PATH = "/realms/cce/protocol/openid-connect/token";
	private static final String USERS_PATH = "/admin/realms/cce/users";
	private static final String NEW_ID = "9ed584e3-2781-4abe-909e-a4a8a30a0f3e";
	private static final String USER_PATH = USERS_PATH + "/" + NEW_ID;
	private static final String ROLE_MAPPINGS_PATH = USERS_PATH + "/" + NEW_ID + "/role-mappings/realm";
	private static final String AVAILABLE_PATH = ROLE_MAPPINGS_PATH + "/available";
	// As the local Keycloak listed them for a new user: its defaults plus the realm's own roles
	// What a new user holds: only Keycloak's own default role
	private static final String HELD_DEFAULT_ONLY =
			"[{\"id\":\"62603b14-0000-0000-0000-000000000009\",\"name\":\"default-roles-cce\",\"composite\":true,\"clientRole\":false}]";
	// A user who holds NURSE; NURSE is then missing from "available"
	private static final String HELD_NURSE = "["
			+ "{\"id\":\"62603b14-0000-0000-0000-000000000009\",\"name\":\"default-roles-cce\",\"composite\":true,\"clientRole\":false},"
			+ "{\"id\":\"62603b14-0000-0000-0000-000000000003\",\"name\":\"NURSE\",\"composite\":false,\"clientRole\":false}"
			+ "]";
	private static final String AVAILABLE_WITHOUT_NURSE = "["
			+ "{\"id\":\"62603b14-0000-0000-0000-000000000002\",\"name\":\"SUPER_ADMIN\",\"composite\":false,\"clientRole\":false}"
			+ "]";
	private static final String AVAILABLE = "["
			+ "{\"id\":\"62603b14-0000-0000-0000-000000000001\",\"name\":\"offline_access\",\"composite\":false,\"clientRole\":false},"
			+ "{\"id\":\"62603b14-0000-0000-0000-000000000002\",\"name\":\"SUPER_ADMIN\",\"composite\":false,\"clientRole\":false},"
			+ "{\"id\":\"62603b14-0000-0000-0000-000000000003\",\"name\":\"NURSE\",\"composite\":false,\"clientRole\":false}"
			+ "]";

	// A fresh fake Keycloak on a free port; its stubs and recorded requests are cleared before each test
	@RegisterExtension
	static WireMockExtension keycloak = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort())
			.build();

	@BeforeEach
	void tokenEndpoint() {
		keycloak.stubFor(post(urlEqualTo(TOKEN_PATH))
				.willReturn(okJson("{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
	}

	// ---- createUser ----

	@Test
	void createdUserGivesTheIdFromLocation() {
		keycloak.stubFor(post(urlEqualTo(USERS_PATH)).willReturn(aResponse().withStatus(201)
				.withHeader("Location", keycloak.baseUrl() + USERS_PATH + "/" + NEW_ID)));

		String id = client().createUser("alice", "alice@example.org", "Alice", "Otieno");

		assertThat(id).isEqualTo(NEW_ID);
	}

	@Test
	void createSendsAnEnabledUserWithUpdatePasswordAndNoCredentials() {
		keycloak.stubFor(post(urlEqualTo(USERS_PATH)).willReturn(aResponse().withStatus(201)
				.withHeader("Location", keycloak.baseUrl() + USERS_PATH + "/" + NEW_ID)));

		client().createUser("alice", "alice@example.org", "Alice", "Otieno");

		keycloak.verify(1, postRequestedFor(urlEqualTo(USERS_PATH))
				.withRequestBody(equalToJson("{\"username\":\"alice\",\"email\":\"alice@example.org\","
						+ "\"firstName\":\"Alice\",\"lastName\":\"Otieno\",\"enabled\":true,"
						+ "\"requiredActions\":[\"UPDATE_PASSWORD\"]}", false, true))
				.withRequestBody(matchingJsonPath("$.credentials", absent())));
	}

	@Test
	void createSendsNoEmptyFields() {
		keycloak.stubFor(post(urlEqualTo(USERS_PATH)).willReturn(aResponse().withStatus(201)
				.withHeader("Location", keycloak.baseUrl() + USERS_PATH + "/" + NEW_ID)));

		client().createUser("alice", "alice@example.org", "Alice", "Otieno");

		// Keycloak 26.5 refuses fields it does not know, even empty ones, e.g. "verifiableCredentials": null
		keycloak.verify(1, postRequestedFor(urlEqualTo(USERS_PATH)).withRequestBody(notContaining("null")));
	}

	@Test
	void errorFieldIsTheReasonWhenThereIsNoErrorMessage() {
		keycloakUsersAnswer(400, "{\"error\":\"Invalid json representation for UserRepresentation.\"}");

		assertThatThrownBy(() -> client().createUser("alice", "alice@example.org", "Alice", "Otieno"))
				.isInstanceOfSatisfying(KeycloakRejectedException.class, e -> assertThat(e.getReason())
						.isEqualTo("Invalid json representation for UserRepresentation."));
	}

	@Test
	void takenUsernameOrEmailIsAConflict() {
		keycloakUsersAnswer(409, "{\"errorMessage\":\"User exists with same username\"}");

		assertThatThrownBy(() -> client().createUser("alice", "alice@example.org", "Alice", "Otieno"))
				.isInstanceOf(KeycloakConflictException.class)
				.hasMessage("Keycloak at " + keycloak.baseUrl()
						+ ": could not create user alice: User exists with same username");
		keycloak.verify(1, postRequestedFor(urlEqualTo(USERS_PATH)));
	}

	@Test
	void oneRefusedFieldIsRejectedWithItsReason() {
		keycloakUsersAnswer(400,
				"{\"field\":\"username\",\"errorMessage\":\"error-invalid-length\",\"params\":[\"username\",3,255]}");

		assertThatThrownBy(() -> client().createUser("ab", "ab@example.org", "Alice", "Otieno"))
				.isInstanceOfSatisfying(KeycloakRejectedException.class, e -> assertThat(e.getReason())
						.isEqualTo("username error-invalid-length"));
		keycloak.verify(1, postRequestedFor(urlEqualTo(USERS_PATH)));
	}

	@Test
	void severalRefusedFieldsAreJoined() {
		keycloakUsersAnswer(400, "{\"errors\":["
				+ "{\"field\":\"lastName\",\"errorMessage\":\"error-person-name-invalid-character\",\"params\":[\"lastName\"]},"
				+ "{\"field\":\"firstName\",\"errorMessage\":\"error-person-name-invalid-character\",\"params\":[\"firstName\"]}"
				+ "]}");

		assertThatThrownBy(() -> client().createUser("alice", "alice@example.org", "<b>", "U$"))
				.isInstanceOfSatisfying(KeycloakRejectedException.class, e -> assertThat(e.getReason())
						.isEqualTo("lastName error-person-name-invalid-character;"
								+ " firstName error-person-name-invalid-character"));
	}

	@Test
	void serverErrorIsAPlainKeycloakException() {
		keycloakUsersAnswer(500, "");

		assertThatThrownBy(() -> client().createUser("alice", "alice@example.org", "Alice", "Otieno"))
				.isExactlyInstanceOf(KeycloakException.class)
				.hasMessage("Keycloak at " + keycloak.baseUrl() + ": could not create user alice: HTTP 500");
		keycloak.verify(1, postRequestedFor(urlEqualTo(USERS_PATH)));
	}

	// ---- assignRealmRoles ----

	@Test
	void foundRolesAreGivenInOneCall() {
		keycloak.stubFor(get(urlEqualTo(AVAILABLE_PATH)).willReturn(okJson(AVAILABLE)));
		keycloak.stubFor(post(urlEqualTo(ROLE_MAPPINGS_PATH)).willReturn(aResponse().withStatus(204)));

		client().assignRealmRoles(NEW_ID, List.of("NURSE", "SUPER_ADMIN"));

		keycloak.verify(1, getRequestedFor(urlEqualTo(AVAILABLE_PATH)));
		keycloak.verify(1, postRequestedFor(urlEqualTo(ROLE_MAPPINGS_PATH))
				.withRequestBody(equalToJson("[{\"id\":\"62603b14-0000-0000-0000-000000000003\",\"name\":\"NURSE\"},"
						+ "{\"id\":\"62603b14-0000-0000-0000-000000000002\",\"name\":\"SUPER_ADMIN\"}]", false, true)));
	}

	@Test
	void roleMissingInKeycloakIsNamedAndNothingIsGiven() {
		keycloak.stubFor(get(urlEqualTo(AVAILABLE_PATH)).willReturn(okJson(AVAILABLE)));
		keycloak.stubFor(get(urlEqualTo(ROLE_MAPPINGS_PATH)).willReturn(okJson(HELD_DEFAULT_ONLY)));

		assertThatThrownBy(() -> client().assignRealmRoles(NEW_ID, List.of("NURSE", "PHARMACIST")))
				.isExactlyInstanceOf(KeycloakException.class)
				.hasMessage("Keycloak at " + keycloak.baseUrl() + ": could not assign realm roles NURSE, PHARMACIST to user "
						+ NEW_ID + ": Keycloak has no realm role PHARMACIST")
				.satisfies(e -> assertThat(((KeycloakException) e).getReason())
						.isEqualTo("Keycloak has no realm role PHARMACIST"));
		keycloak.verify(0, postRequestedFor(urlEqualTo(ROLE_MAPPINGS_PATH)));
	}

	@Test
	void failedGivingIsAKeycloakException() {
		keycloak.stubFor(get(urlEqualTo(AVAILABLE_PATH)).willReturn(okJson(AVAILABLE)));
		keycloak.stubFor(post(urlEqualTo(ROLE_MAPPINGS_PATH)).willReturn(aResponse().withStatus(500)));

		assertThatThrownBy(() -> client().assignRealmRoles(NEW_ID, List.of("NURSE")))
				.isExactlyInstanceOf(KeycloakException.class)
				.hasMessageStartingWith("Keycloak at " + keycloak.baseUrl() + ": could not assign realm roles NURSE to user "
						+ NEW_ID + ": ");
		keycloak.verify(1, postRequestedFor(urlEqualTo(ROLE_MAPPINGS_PATH)));
	}

	// ---- deleteUser ----

	@Test
	void deletedUserIsDone() {
		keycloak.stubFor(delete(urlEqualTo(USER_PATH)).willReturn(aResponse().withStatus(204)));

		assertThatCode(() -> client().deleteUser(NEW_ID)).doesNotThrowAnyException();

		keycloak.verify(1, deleteRequestedFor(urlEqualTo(USER_PATH)));
	}

	@Test
	void userAlreadyGoneCountsAsDeleted() {
		keycloak.stubFor(delete(urlEqualTo(USER_PATH)).willReturn(aResponse().withStatus(404)
				.withHeader("Content-Type", "application/json").withBody("{\"error\":\"User not found\"}")));

		assertThatCode(() -> client().deleteUser(NEW_ID)).doesNotThrowAnyException();
	}

	@Test
	void failedDeleteIsAKeycloakExceptionAndNotRetried() {
		keycloak.stubFor(delete(urlEqualTo(USER_PATH)).willReturn(aResponse().withStatus(500)));

		assertThatThrownBy(() -> client().deleteUser(NEW_ID))
				.isExactlyInstanceOf(KeycloakException.class)
				.hasMessage("Keycloak at " + keycloak.baseUrl() + ": could not delete user " + NEW_ID + ": HTTP 500")
				.satisfies(e -> assertThat(((KeycloakException) e).getReason()).isEqualTo("HTTP 500"));
		keycloak.verify(1, deleteRequestedFor(urlEqualTo(USER_PATH)));
	}

	// ---- updateUser ----

	@Test
	void updateSendsOnlyEmailAndNames() {
		keycloak.stubFor(put(urlEqualTo(USER_PATH)).willReturn(aResponse().withStatus(204)));

		client().updateUser(NEW_ID, "alice.o@example.org", "Alice", "Otieno");

		// Strict: exactly these three fields, so Keycloak keeps enabled, required actions and the rest
		keycloak.verify(1, putRequestedFor(urlEqualTo(USER_PATH)).withRequestBody(equalToJson(
				"{\"email\":\"alice.o@example.org\",\"firstName\":\"Alice\",\"lastName\":\"Otieno\"}", false,
				false)));
	}

	@Test
	void emailOfAnotherKeycloakAccountIsAConflict() {
		keycloakUserAnswers(409, "{\"errorMessage\":\"User exists with same email\"}");

		assertThatThrownBy(() -> client().updateUser(NEW_ID, "taken@example.org", "Alice", "Otieno"))
				.isInstanceOf(KeycloakConflictException.class)
				.hasMessage("Keycloak at " + keycloak.baseUrl() + ": could not update user " + NEW_ID
						+ ": User exists with same email");
		keycloak.verify(1, putRequestedFor(urlEqualTo(USER_PATH)));
	}

	@Test
	void refusedNameOnUpdateIsRejectedWithItsReason() {
		keycloakUserAnswers(400,
				"{\"field\":\"firstName\",\"errorMessage\":\"error-person-name-invalid-character\",\"params\":[\"firstName\"]}");

		assertThatThrownBy(() -> client().updateUser(NEW_ID, "alice@example.org", "<b>", "Otieno"))
				.isInstanceOfSatisfying(KeycloakRejectedException.class, e -> assertThat(e.getReason())
						.isEqualTo("firstName error-person-name-invalid-character"));
	}

	@Test
	void unknownKeycloakUserOnUpdateIsAPlainFailure() {
		keycloakUserAnswers(404, "{\"error\":\"User not found\"}");

		assertThatThrownBy(() -> client().updateUser(NEW_ID, "alice@example.org", "Alice", "Otieno"))
				.isExactlyInstanceOf(KeycloakException.class)
				.satisfies(e -> assertThat(((KeycloakException) e).getReason()).isEqualTo("User not found"));
		keycloak.verify(1, putRequestedFor(urlEqualTo(USER_PATH)));
	}

	// ---- assignRealmRoles: roles the user already has ----

	@Test
	void roleTheUserAlreadyHasIsSkipped() {
		keycloak.stubFor(get(urlEqualTo(AVAILABLE_PATH)).willReturn(okJson(AVAILABLE_WITHOUT_NURSE)));
		keycloak.stubFor(get(urlEqualTo(ROLE_MAPPINGS_PATH)).willReturn(okJson(HELD_NURSE)));
		keycloak.stubFor(post(urlEqualTo(ROLE_MAPPINGS_PATH)).willReturn(aResponse().withStatus(204)));

		client().assignRealmRoles(NEW_ID, List.of("NURSE", "SUPER_ADMIN"));

		keycloak.verify(1, postRequestedFor(urlEqualTo(ROLE_MAPPINGS_PATH)).withRequestBody(equalToJson(
				"[{\"id\":\"62603b14-0000-0000-0000-000000000002\",\"name\":\"SUPER_ADMIN\"}]", false, true)));
	}

	@Test
	void whenEveryRoleIsAlreadyThereNothingIsGiven() {
		keycloak.stubFor(get(urlEqualTo(AVAILABLE_PATH)).willReturn(okJson(AVAILABLE_WITHOUT_NURSE)));
		keycloak.stubFor(get(urlEqualTo(ROLE_MAPPINGS_PATH)).willReturn(okJson(HELD_NURSE)));

		client().assignRealmRoles(NEW_ID, List.of("NURSE"));

		keycloak.verify(0, postRequestedFor(urlEqualTo(ROLE_MAPPINGS_PATH)));
	}

	// ---- removeRealmRoles ----

	@Test
	void heldRolesAreRemovedInOneCall() {
		keycloak.stubFor(get(urlEqualTo(ROLE_MAPPINGS_PATH)).willReturn(okJson(HELD_NURSE)));
		keycloak.stubFor(delete(urlEqualTo(ROLE_MAPPINGS_PATH)).willReturn(aResponse().withStatus(204)));

		client().removeRealmRoles(NEW_ID, List.of("NURSE", "PHARMACIST"));

		// PHARMACIST is not held, so only NURSE is sent
		keycloak.verify(1, deleteRequestedFor(urlEqualTo(ROLE_MAPPINGS_PATH)).withRequestBody(equalToJson(
				"[{\"id\":\"62603b14-0000-0000-0000-000000000003\",\"name\":\"NURSE\"}]", false, true)));
	}

	@Test
	void rolesNotHeldAreAlreadyRemoved() {
		keycloak.stubFor(get(urlEqualTo(ROLE_MAPPINGS_PATH)).willReturn(okJson(HELD_DEFAULT_ONLY)));

		client().removeRealmRoles(NEW_ID, List.of("NURSE"));

		keycloak.verify(0, deleteRequestedFor(urlEqualTo(ROLE_MAPPINGS_PATH)));
	}

	@Test
	void failedRemoveIsAKeycloakException() {
		keycloak.stubFor(get(urlEqualTo(ROLE_MAPPINGS_PATH)).willReturn(okJson(HELD_NURSE)));
		keycloak.stubFor(delete(urlEqualTo(ROLE_MAPPINGS_PATH)).willReturn(aResponse().withStatus(500)));

		assertThatThrownBy(() -> client().removeRealmRoles(NEW_ID, List.of("NURSE")))
				.isExactlyInstanceOf(KeycloakException.class)
				.hasMessageStartingWith("Keycloak at " + keycloak.baseUrl() + ": could not remove realm roles NURSE from user "
						+ NEW_ID + ": ");
		keycloak.verify(1, deleteRequestedFor(urlEqualTo(ROLE_MAPPINGS_PATH)));
	}

	// ---- setEnabled ----

	@Test
	void setEnabledSendsOnlyTheFlag() {
		keycloak.stubFor(put(urlEqualTo(USER_PATH)).willReturn(aResponse().withStatus(204)));

		client().setEnabled(NEW_ID, false);

		keycloak.verify(1, putRequestedFor(urlEqualTo(USER_PATH))
				.withRequestBody(equalToJson("{\"enabled\":false}", false, false)));
	}

	@Test
	void setEnabledOnAnUnknownUserIsAFailure() {
		keycloakUserAnswers(404, "{\"error\":\"User not found\"}");

		assertThatThrownBy(() -> client().setEnabled(NEW_ID, true))
				.isExactlyInstanceOf(KeycloakException.class)
				.hasMessage("Keycloak at " + keycloak.baseUrl() + ": could not set enabled=true for user " + NEW_ID
						+ ": User not found");
	}

	// ---- sendUpdatePasswordEmail ----

	@Test
	void resetAsksKeycloakToEmailUpdatePassword() {
		keycloak.stubFor(put(urlEqualTo(USER_PATH + "/execute-actions-email")).willReturn(aResponse().withStatus(204)));

		client().sendUpdatePasswordEmail(NEW_ID);

		keycloak.verify(1, putRequestedFor(urlEqualTo(USER_PATH + "/execute-actions-email"))
				.withRequestBody(equalToJson("[\"UPDATE_PASSWORD\"]", false, false)));
	}

	@Test
	void resetWithoutAMailServerGivesKeycloaksReason() {
		keycloak.stubFor(put(urlEqualTo(USER_PATH + "/execute-actions-email")).willReturn(aResponse().withStatus(500)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"errorMessage\":\"Failed to send execute actions email: Invalid sender address 'null'.\"}")));

		assertThatThrownBy(() -> client().sendUpdatePasswordEmail(NEW_ID))
				.isExactlyInstanceOf(KeycloakException.class)
				.satisfies(e -> assertThat(((KeycloakException) e).getReason())
						.isEqualTo("Failed to send execute actions email: Invalid sender address 'null'."));
		keycloak.verify(1, putRequestedFor(urlEqualTo(USER_PATH + "/execute-actions-email")));
	}

	private void keycloakUserAnswers(int status, String body) {
		keycloak.stubFor(put(urlEqualTo(USER_PATH)).willReturn(aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json").withBody(body)));
	}

	private void keycloakUsersAnswer(int status, String body) {
		keycloak.stubFor(post(urlEqualTo(USERS_PATH)).willReturn(aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json").withBody(body)));
	}

	private KeycloakAdminClient client() {
		KeycloakProperties properties = new KeycloakProperties(keycloak.baseUrl(), "cce", "cce-admin-service",
				"test-secret");
		return new KeycloakAdminClient(properties, Duration.ofSeconds(2));
	}
}
