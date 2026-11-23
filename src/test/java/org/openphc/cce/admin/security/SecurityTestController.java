package org.openphc.cce.admin.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint behind security, so the checks can be tested before real endpoints exist. */
@RestController
class SecurityTestController {

	@GetMapping("/test/secure")
	String secure() {
		return "ok";
	}
}
