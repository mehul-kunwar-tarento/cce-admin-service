package org.openphc.cce.admin.audit;

/** What an audit_log row records. Each feature adds the actions it writes. */
public enum AuditAction {
	BOOTSTRAP_ADMIN_LINKED,
	USER_DEACTIVATED,
	USER_ROLES_UPDATED,
	ROLE_UPDATED,
	ROLE_DELETED,
	ROLE_MENUS_UPDATED,
	MENU_CREATED,
	MENU_UPDATED,

	// A change that reached Keycloak but not our database (or the reverse), kept for an operator to fix
	USER_CREATE_FAILED,
	USER_UPDATE_FAILED,
	USER_STATUS_UPDATE_FAILED,
	USER_ROLES_UPDATE_FAILED
}
