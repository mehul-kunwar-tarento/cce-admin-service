package org.openphc.cce.admin.client;

/** The gateway sync did not fully succeed; the message is the reason, for the caller to put in its 502. */
public class GatewaySyncException extends RuntimeException {

	public GatewaySyncException(String reason) {
		super(reason);
	}
}
