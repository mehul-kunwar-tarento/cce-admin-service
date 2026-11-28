package org.openphc.cce.admin.dto;

import org.openphc.cce.admin.entity.UserStatus;

import jakarta.validation.constraints.NotNull;

/** Body of PATCH /users/{id}/status. A value other than ACTIVE, INACTIVE or LOCKED is refused as bad JSON. */
public record UserStatusRequest(@NotNull UserStatus status) {
}
