package org.openphc.cce.admin.security;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Records when a user last logged in, from the token's auth_time (the moment they entered their
 * password in Keycloak). One write per login; it never blocks the request.
 */
@Component
public class LastLoginRecorder {

	private static final Logger log = LoggerFactory.getLogger(LastLoginRecorder.class);

	private final UserRepository userRepository;
	/** Missing auth_time is logged once per app start, not once per request. */
	private final AtomicBoolean missingAuthTimeLogged = new AtomicBoolean(false);

	public LastLoginRecorder(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	public void record(User user, Jwt token) {
		Instant authTime = authTimeOf(token);
		if (authTime == null) {
			logMissingAuthTimeOnce();
			return;
		}

		Instant lastLogin = user.getLastLoginAt();
		if (lastLogin != null && !authTime.isAfter(lastLogin)) {
			// Same login as before (or an older token): nothing to write
			return;
		}

		try {
			userRepository.moveLastLoginForward(user.getId(), authTime);
		} catch (RuntimeException e) {
			log.warn("Could not update last_login_at for user {}; the request goes on: {}",
					user.getId(), e.getMessage());
		}
	}

	/** auth_time is a number of seconds since 1970; null when the token has none. */
	private Instant authTimeOf(Jwt token) {
		Object value = token.getClaims().get("auth_time");
		if (value instanceof Instant instant) {
			return instant;
		}
		if (value instanceof Number seconds) {
			return Instant.ofEpochSecond(seconds.longValue());
		}
		return null;
	}

	private void logMissingAuthTimeOnce() {
		// compareAndSet flips the flag only for the first caller, even if two requests arrive together
		if (missingAuthTimeLogged.compareAndSet(false, true)) {
			log.info("Token has no auth_time claim; last_login_at is not updated for such tokens. (Logged once.)");
		}
	}
}
