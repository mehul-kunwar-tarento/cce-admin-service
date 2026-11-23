package org.openphc.cce.admin.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints behind security, so the checks can be tested before real endpoints exist. */
@RestController
class SecurityTestController {

	@GetMapping("/test/secure")
	String secure() {
		return "ok";
	}

	/** The CurrentUser the security checks put on the request. */
	@GetMapping("/test/current-user")
	CurrentUser currentUser(HttpServletRequest request) {
		return CurrentUser.get(request);
	}
}
