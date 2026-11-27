package org.openphc.cce.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/** The body of POST /users on its own: cleaning in the constructor, then the field checks. No app start. */
class CreateUserRequestTest {

	private static final UUID ROLE = UUID.fromString("a91f0000-0000-0000-0000-000000000001");

	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void startValidator() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		factory.close();
	}

	@Test
	void usernameAndEmailAreTrimmedAndLowercased() {
		CreateUserRequest request = request(" Alice ", " ALICE@Example.ORG ", "Alice", "Otieno", List.of(ROLE));

		assertThat(request.username()).isEqualTo("alice");
		assertThat(request.email()).isEqualTo("alice@example.org");
		assertThat(failedFields(request)).isEmpty();
	}

	@Test
	void namesKeepTheirCapitals() {
		CreateUserRequest request = request("alice", "alice@example.org", "Alice", "McOtieno", List.of(ROLE));

		assertThat(request.firstName()).isEqualTo("Alice");
		assertThat(request.lastName()).isEqualTo("McOtieno");
	}

	@Test
	void usernameShorterThanThreeAfterTrimmingIsRefused() {
		CreateUserRequest request = request("  ab  ", "ab@example.org", "Alice", "Otieno", List.of(ROLE));

		assertThat(failedFields(request)).containsExactly("username size must be between 3 and 255");
	}

	@Test
	void valuesLongerThanTheirColumnsAreRefused() {
		String tooLong = "a".repeat(256);
		CreateUserRequest request = request(tooLong, "alice@example.org", tooLong, tooLong, List.of(ROLE));

		assertThat(failedFields(request)).containsExactlyInAnyOrder("username size must be between 3 and 255",
				"firstName size must be between 0 and 255", "lastName size must be between 0 and 255");
	}

	@Test
	void badEmailIsRefused() {
		CreateUserRequest request = request("alice", "not-an-email", "Alice", "Otieno", List.of(ROLE));

		assertThat(failedFields(request)).containsExactly("email must be a well-formed email address");
	}

	@Test
	void missingOrBlankFieldsAreRefused() {
		CreateUserRequest request = request(null, "  ", " ", null, List.of(ROLE));

		assertThat(failedFields(request)).contains("username must not be blank", "email must not be blank",
				"firstName must not be blank", "lastName must not be blank");
	}

	@Test
	void emptyOrMissingRoleListIsRefused() {
		assertThat(failedFields(request("alice", "alice@example.org", "Alice", "Otieno", List.of())))
				.containsExactly("roleIds must not be empty");
		assertThat(failedFields(request("alice", "alice@example.org", "Alice", "Otieno", null)))
				.containsExactly("roleIds must not be empty");
	}

	@Test
	void nullRoleIdIsRefusedWithItsPosition() {
		List<UUID> roleIds = Arrays.asList(ROLE, null);

		// The validator's own path; the 400 message shows it as "roleIds[1]" (controller test)
		assertThat(failedFields(request("alice", "alice@example.org", "Alice", "Otieno", roleIds)))
				.containsExactly("roleIds[1].<list element> must not be null");
	}

	@Test
	void passwordInTheBodyIsIgnored() throws Exception {
		// Spring Boot's own JSON reader skips unknown fields; this one is set up the same way
		ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		String body = "{\"username\":\"alice\",\"email\":\"alice@example.org\",\"firstName\":\"Alice\","
				+ "\"lastName\":\"Otieno\",\"roleIds\":[\"" + ROLE + "\"],\"password\":\"Secret123!\"}";

		CreateUserRequest request = json.readValue(body, CreateUserRequest.class);

		assertThat(failedFields(request)).isEmpty();
		assertThat(request.toString()).doesNotContain("Secret123!");
	}

	private CreateUserRequest request(String username, String email, String firstName, String lastName,
			List<UUID> roleIds) {
		return new CreateUserRequest(username, email, firstName, lastName, null, roleIds);
	}

	/** Each failed check as "field message", e.g. "username must not be blank". */
	private List<String> failedFields(CreateUserRequest request) {
		Set<ConstraintViolation<CreateUserRequest>> violations = validator.validate(request);
		List<String> fields = new ArrayList<>();
		for (ConstraintViolation<CreateUserRequest> violation : violations) {
			fields.add(violation.getPropertyPath() + " " + violation.getMessage());
		}
		return fields;
	}
}
