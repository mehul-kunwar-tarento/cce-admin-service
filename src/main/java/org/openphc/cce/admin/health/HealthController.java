package org.openphc.cce.admin.health;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /admin/v1/health: the app is up; the database state is information only. */
@RestController
public class HealthController {

	private final DatabaseCheck databaseCheck;

	public HealthController(DatabaseCheck databaseCheck) {
		this.databaseCheck = databaseCheck;
	}

	@GetMapping("/admin/v1/health")
	// Open without a token, so the API docs show no lock here
	@SecurityRequirements
	public HealthResponse health() {
		String db = "DOWN";
		if (databaseCheck.isUp()) {
			db = "UP";
		}
		// Always 200 and status UP: a database outage must not make the platform restart a healthy app
		return new HealthResponse("UP", db);
	}
}
