package org.openphc.cce.admin.security;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

/** The caller of the current request, once the security checks have passed. */
public record CurrentUser(UUID id, String username, List<String> roles) {

	private static final String ATTRIBUTE = CurrentUser.class.getName();

	public static void set(HttpServletRequest request, CurrentUser user) {
		request.setAttribute(ATTRIBUTE, user);
	}

	/** Null on open paths, where no checks ran. */
	public static CurrentUser get(HttpServletRequest request) {
		return (CurrentUser) request.getAttribute(ATTRIBUTE);
	}
}
