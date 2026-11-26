package org.openphc.cce.admin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.config.KeycloakProperties;

/**
 * What the client does when Keycloak is down or silent. No Keycloak needed: a closed port plays "down",
 * a socket that accepts but never answers plays "silent". Real Keycloak behaviour is checked in a real run.
 */
class KeycloakAdminClientTest {

	// Filled by the silent server's own thread
	private final List<Socket> heldConnections = Collections.synchronizedList(new ArrayList<>());
	private ServerSocket silentServer;

	@AfterEach
	void closeSockets() throws IOException {
		// Server first, so its thread stops adding connections while they are closed
		if (silentServer != null) {
			silentServer.close();
		}
		for (Socket connection : heldConnections) {
			connection.close();
		}
	}

	@Test
	void timeoutIsTenSeconds() {
		assertThat(KeycloakAdminClient.TIMEOUT).isEqualTo(Duration.ofSeconds(10));
	}

	@Test
	void unreachableKeycloakIsAKeycloakException() throws IOException {
		String url = "http://localhost:" + closedPort();
		KeycloakAdminClient client = new KeycloakAdminClient(properties(url), Duration.ofSeconds(1));

		assertThatThrownBy(() -> client.findUserByUsername("superadmin"))
				.isInstanceOf(KeycloakException.class)
				.hasMessageStartingWith("Keycloak at " + url + ": could not find user superadmin: ")
				.hasMessageContaining("refused");
	}

	@Test
	void silentKeycloakGivesUpAfterTheTimeout() throws IOException {
		String url = "http://localhost:" + startSilentServer();
		KeycloakAdminClient client = new KeycloakAdminClient(properties(url), Duration.ofSeconds(1));

		long start = System.nanoTime();
		assertThatThrownBy(() -> client.hasRealmRole("kc-1", "SUPER_ADMIN"))
				.isInstanceOf(KeycloakException.class)
				.hasMessageStartingWith("Keycloak at " + url + ": could not read the realm roles of user kc-1: ");
		Duration waited = Duration.ofNanos(System.nanoTime() - start);

		assertThat(waited).isLessThan(Duration.ofSeconds(2));
	}

	private KeycloakProperties properties(String baseUrl) {
		return new KeycloakProperties(baseUrl, "cce", "cce-admin-service", "test-secret");
	}

	/** A port nothing listens on: open one, note its number, close it again. */
	private int closedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	/** Accepts connections and reads nothing, answers nothing. */
	private int startSilentServer() throws IOException {
		silentServer = new ServerSocket(0);
		Thread acceptor = new Thread(() -> {
			try {
				while (true) {
					heldConnections.add(silentServer.accept());
				}
			} catch (IOException closed) {
				// Server closed at the end of the test
			}
		});
		acceptor.setDaemon(true);
		acceptor.start();
		return silentServer.getLocalPort();
	}
}
