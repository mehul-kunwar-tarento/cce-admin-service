package org.openphc.cce.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.repository.UserRepository;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jwt.Jwt;

/** The login-time rules on their own: repository and user are stand-ins, so "was an UPDATE issued?" can be checked. */
@ExtendWith(OutputCaptureExtension.class)
class LastLoginRecorderTest {

	private static final UUID USER_ID = UUID.randomUUID();
	private static final Instant LOGIN_TIME = Instant.parse("2026-11-23T10:15:00Z");

	private final UserRepository userRepository = mock(UserRepository.class);
	private final LastLoginRecorder recorder = new LastLoginRecorder(userRepository);

	@Test
	void firstLoginSetsLastLogin() {
		recorder.record(userWithLastLogin(null), tokenWithAuthTime(LOGIN_TIME));

		verify(userRepository).moveLastLoginForward(USER_ID, LOGIN_TIME);
	}

	@Test
	void sameLoginTimeWritesNothing() {
		recorder.record(userWithLastLogin(LOGIN_TIME), tokenWithAuthTime(LOGIN_TIME));

		verify(userRepository, never()).moveLastLoginForward(any(), any());
	}

	@Test
	void olderLoginTimeWritesNothing() {
		Instant anHourEarlier = LOGIN_TIME.minusSeconds(3600);

		recorder.record(userWithLastLogin(LOGIN_TIME), tokenWithAuthTime(anHourEarlier));

		verify(userRepository, never()).moveLastLoginForward(any(), any());
	}

	@Test
	void missingAuthTimeIsLoggedOnce(CapturedOutput output) {
		User user = userWithLastLogin(null);

		recorder.record(user, tokenWithoutAuthTime());
		recorder.record(user, tokenWithoutAuthTime());

		verify(userRepository, never()).moveLastLoginForward(any(), any());
		String line = "Token has no auth_time claim";
		assertThat(output.getOut().split(line, -1)).hasSize(2);
	}

	@Test
	void failedUpdateIsAWarningNotAnError(CapturedOutput output) {
		when(userRepository.moveLastLoginForward(USER_ID, LOGIN_TIME))
				.thenThrow(new DataAccessResourceFailureException("Connection refused"));

		assertThatCode(() -> recorder.record(userWithLastLogin(null), tokenWithAuthTime(LOGIN_TIME)))
				.doesNotThrowAnyException();
		assertThat(output.getOut()).contains("WARN").contains("Could not update last_login_at for user " + USER_ID);
	}

	// ---- helpers ----

	private User userWithLastLogin(Instant lastLogin) {
		User user = mock(User.class);
		when(user.getId()).thenReturn(USER_ID);
		when(user.getLastLoginAt()).thenReturn(lastLogin);
		return user;
	}

	/** auth_time as Keycloak sends it: seconds since 1970. */
	private Jwt tokenWithAuthTime(Instant authTime) {
		return tokenBuilder().claim("auth_time", authTime.getEpochSecond()).build();
	}

	private Jwt tokenWithoutAuthTime() {
		return tokenBuilder().build();
	}

	private Jwt.Builder tokenBuilder() {
		return Jwt.withTokenValue("test-token").header("alg", "RS256").subject("kc-asha");
	}
}
