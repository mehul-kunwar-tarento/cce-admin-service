package org.openphc.cce.admin.dto;

import org.openphc.cce.admin.entity.UserStatus;

/** A user's status, as status audit rows show it before and after. */
public record StatusSnapshot(UserStatus status) {
}
