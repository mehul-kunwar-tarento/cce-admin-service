package org.openphc.cce.admin.dto;

import java.util.List;

/** What a password-reset audit row shows: where the link went and what it asks. Never a password. */
public record PasswordResetSnapshot(String email, List<String> actions) {
}
