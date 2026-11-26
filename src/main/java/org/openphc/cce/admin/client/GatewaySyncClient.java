package org.openphc.cce.admin.client;

import java.util.UUID;

/** Asks the gateway to reload its permission rules and match Keycloak's realm roles to them. */
public interface GatewaySyncClient {

	/**
	 * Returns when the gateway reports full success; otherwise throws GatewaySyncException with the reason.
	 * The role id only goes into the log. Never retried.
	 */
	void sync(UUID roleId);
}
