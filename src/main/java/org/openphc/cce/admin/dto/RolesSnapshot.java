package org.openphc.cce.admin.dto;

import java.util.List;

/** A user's roles as role-change audit rows show them before and after: names, A to Z. */
public record RolesSnapshot(List<String> roles) {
}
