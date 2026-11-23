package org.openphc.cce.admin.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.error.ErrorResponseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Answers 401 in the usual {"error":{"code","message"}} shape. Spring Security rejects the request
 * before any controller runs, so the global exception handler never sees it. The message never says
 * which check failed; the reason goes to the DEBUG log only.
 */
@Component
public class JsonAuthEntryPoint implements AuthenticationEntryPoint {

	private static final Logger log = LoggerFactory.getLogger(JsonAuthEntryPoint.class);
	private static final String MESSAGE = "Missing or invalid token.";

	private final ErrorResponseWriter errorResponseWriter;

	public JsonAuthEntryPoint(ErrorResponseWriter errorResponseWriter) {
		this.errorResponseWriter = errorResponseWriter;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
			throws IOException {
		log.debug("401 for {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());

		// Standard for 401: tells the caller a Bearer token is expected
		response.setHeader("WWW-Authenticate", "Bearer");
		errorResponseWriter.write(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, MESSAGE);
	}
}
