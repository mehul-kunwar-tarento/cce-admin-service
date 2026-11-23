package org.openphc.cce.admin.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.openphc.cce.admin.config.SecurityConfig;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.error.ErrorResponseWriter;
import org.openphc.cce.admin.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Second check on every protected request, after the token check: the token's sub must belong to a
 * local user whose status is ACTIVE, otherwise 403. A user deactivated a minute ago still has a valid
 * token, so only this table can stop them. The user who passes is put on the request as CurrentUser.
 */
public class ActiveUserFilter extends OncePerRequestFilter {

	static final String NOT_SET_UP =
			"Your account is not set up in the admin service. Ask an administrator to add you.";
	static final String NOT_ACTIVE = "Your account is not active. Ask an administrator to activate it.";

	private final UserRepository userRepository;
	private final RolesClaimReader rolesClaimReader;
	private final ErrorResponseWriter errorResponseWriter;
	private final HandlerExceptionResolver exceptionResolver;
	private final AntPathMatcher pathMatcher = new AntPathMatcher();

	public ActiveUserFilter(UserRepository userRepository, RolesClaimReader rolesClaimReader,
			ErrorResponseWriter errorResponseWriter, HandlerExceptionResolver exceptionResolver) {
		this.userRepository = userRepository;
		this.rolesClaimReader = rolesClaimReader;
		this.errorResponseWriter = errorResponseWriter;
		this.exceptionResolver = exceptionResolver;
	}

	/** Health and the API docs need no token, so they skip this check too. */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		for (String openPath : SecurityConfig.OPEN_PATHS) {
			if (pathMatcher.match(openPath, request.getRequestURI())) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Jwt token = tokenOf(SecurityContextHolder.getContext().getAuthentication());
		if (token == null) {
			// No token: the next security step answers 401
			chain.doFilter(request, response);
			return;
		}

		Optional<User> found;
		try {
			found = userRepository.findByKeycloakId(token.getSubject());
		} catch (RuntimeException e) {
			// e.g. database down: the global error handler answers, as for any other failure
			exceptionResolver.resolveException(request, response, null, e);
			return;
		}

		if (found.isEmpty()) {
			errorResponseWriter.write(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, NOT_SET_UP);
			return;
		}
		User user = found.get();
		if (user.getStatus() != UserStatus.ACTIVE) {
			errorResponseWriter.write(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, NOT_ACTIVE);
			return;
		}

		List<String> roles = rolesClaimReader.read(token);
		CurrentUser.set(request, new CurrentUser(user.getId(), user.getUsername(), roles));
		chain.doFilter(request, response);
	}

	private Jwt tokenOf(Authentication authentication) {
		if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
			return jwtAuthentication.getToken();
		}
		return null;
	}
}
