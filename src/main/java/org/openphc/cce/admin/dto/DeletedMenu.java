package org.openphc.cce.admin.dto;

import java.util.List;
import java.util.UUID;

/** Audit before of a deleted menu: the menu itself and the roles that had it. */
public record DeletedMenu(MenuSnapshot menu, List<UUID> roleIds) {
}
