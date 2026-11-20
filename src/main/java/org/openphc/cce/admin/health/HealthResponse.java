package org.openphc.cce.admin.health;

/** Body of GET /admin/v1/health. Not wrapped in {data}: callers expect the bare {status, db}. */
public record HealthResponse(String status, String db) {
}
