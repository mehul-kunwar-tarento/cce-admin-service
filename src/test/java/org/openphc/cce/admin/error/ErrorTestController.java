package org.openphc.cce.admin.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.openphc.cce.admin.client.GatewaySyncException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints that raise each kind of error, before real endpoints exist. */
@RestController
class ErrorTestController {

	record TestBody(@NotBlank String name) {
	}

	@PostMapping("/test/body")
	String body(@Valid @RequestBody TestBody body) {
		return body.name();
	}

	@GetMapping("/test/api-exception")
	String apiException() {
		throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.ROLE_NOT_FOUND, "x");
	}

	@GetMapping("/test/gateway-sync")
	String gatewaySync() {
		throw new GatewaySyncException("Gateway not reachable at http://test-gateway.");
	}

	@GetMapping("/test/boom")
	String boom() {
		throw new RuntimeException("secret detail");
	}
}
