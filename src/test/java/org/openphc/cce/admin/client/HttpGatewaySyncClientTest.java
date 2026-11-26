package org.openphc.cce.admin.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openphc.cce.admin.config.GatewayProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/** Every gateway reply, played by WireMock (a fake HTTP server), and what the client makes of it. */
@ExtendWith(OutputCaptureExtension.class)
class HttpGatewaySyncClientTest {

	private static final String SYNC_PATH = "/api/permissions/sync-to-keycloak";
	private static final UUID ROLE_ID = UUID.fromString("7c1e0000-0000-0000-0000-000000000001");

	// A fresh fake gateway on a free port; its stubs and recorded requests are cleared before each test
	@RegisterExtension
	static WireMockExtension gateway = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort())
			.build();

	// ---- success ----

	@Test
	void successIsOk() {
		gatewayAnswers(200, "{\"success\":true,\"message\":\"Synced 6 permissions successfully\"}");

		assertThatCode(() -> client().sync(ROLE_ID)).doesNotThrowAnyException();
	}

	// ---- the gateway answered, but not with success ----

	@Test
	void successFalseIsAFailure() {
		gatewayAnswers(200, "{\"success\":false,\"message\":\"Half done\"}");

		assertThatThrownBy(() -> client().sync(ROLE_ID)).isInstanceOf(GatewaySyncException.class)
				.hasMessage("Gateway sync error: Half done.");
	}

	@Test
	void bodyThatIsNotJsonIsAFailure() {
		gatewayAnswers(200, "<html>Welcome to some other service</html>");

		assertThatThrownBy(() -> client().sync(ROLE_ID)).isInstanceOf(GatewaySyncException.class)
				.hasMessage("Gateway sync error: HTTP 200.");
	}

	@Test
	void partialFailureNamesTheRoles() {
		gatewayAnswers(206, "{\"success\":false,\"failedRoles\":[\"A\",\"B\"],\"failureCount\":2}");

		assertThatThrownBy(() -> client().sync(ROLE_ID)).isInstanceOf(GatewaySyncException.class)
				.hasMessage("Gateway reloaded its rules, but Keycloak role sync failed for: A, B.");
	}

	@Test
	void serverErrorGivesTheGatewayMessage() {
		gatewayAnswers(500, "{\"success\":false,\"message\":\"X\"}");

		assertThatThrownBy(() -> client().sync(ROLE_ID)).isInstanceOf(GatewaySyncException.class)
				.hasMessage("Gateway sync error: X.");
	}

	@Test
	void messageEndingInAPeriodGetsNoSecondOne() {
		gatewayAnswers(500, "{\"success\":false,\"message\":\"Keycloak is down.\"}");

		assertThatThrownBy(() -> client().sync(ROLE_ID)).hasMessage("Gateway sync error: Keycloak is down.");
	}

	@Test
	void notFoundWithoutMessageGivesTheStatus() {
		gatewayAnswers(404, "");

		assertThatThrownBy(() -> client().sync(ROLE_ID)).isInstanceOf(GatewaySyncException.class)
				.hasMessage("Gateway sync error: HTTP 404.");
	}

	// ---- the gateway never answered ----

	@Test
	void unreachableGatewayNamesTheUrl() throws IOException {
		String url = "http://localhost:" + closedPort();
		HttpGatewaySyncClient client = new HttpGatewaySyncClient(new GatewayProperties(url), new ObjectMapper(),
				Duration.ofSeconds(1));

		assertThatThrownBy(() -> client.sync(ROLE_ID)).isInstanceOf(GatewaySyncException.class)
				.hasMessage("Gateway not reachable at " + url + ".");
	}

	@Test
	void silentGatewayGivesUpAfterTheTimeout() {
		gatewayAnswersAfter(Duration.ofSeconds(3));

		long start = System.nanoTime();
		assertThatThrownBy(() -> client().sync(ROLE_ID)).isInstanceOf(GatewaySyncException.class)
				.hasMessage("Gateway did not answer within 1s.");
		Duration waited = Duration.ofNanos(System.nanoTime() - start);

		assertThat(waited).isLessThan(Duration.ofSeconds(2));
	}

	@Test
	void timeoutIsTenSeconds() {
		assertThat(HttpGatewaySyncClient.TIMEOUT).isEqualTo(Duration.ofSeconds(10));
	}

	// ---- the request itself ----

	@Test
	void requestIsAPostWithoutAuthorization() {
		gatewayAnswers(200, "{\"success\":true}");

		client().sync(ROLE_ID);

		gateway.verify(1, postRequestedFor(urlEqualTo(SYNC_PATH)).withHeader("Authorization", absent()));
	}

	@Test
	void neverRetried() {
		gatewayAnswers(500, "{\"success\":false,\"message\":\"X\"}");
		assertThatThrownBy(() -> client().sync(ROLE_ID)).isInstanceOf(GatewaySyncException.class);
		gateway.verify(1, postRequestedFor(urlEqualTo(SYNC_PATH)));

		gateway.resetAll();
		gatewayAnswersAfter(Duration.ofSeconds(3));
		assertThatThrownBy(() -> client().sync(ROLE_ID)).isInstanceOf(GatewaySyncException.class);
		gateway.verify(1, postRequestedFor(urlEqualTo(SYNC_PATH)));
	}

	// ---- the log ----

	@Test
	void everyFailureLogsOneWarning(CapturedOutput output) throws IOException {
		String syncUrl = gateway.getRuntimeInfo().getHttpBaseUrl() + SYNC_PATH;

		gatewayAnswers(206, "{\"success\":false,\"failedRoles\":[\"A\"]}");
		assertThatThrownBy(() -> client().sync(ROLE_ID)).isInstanceOf(GatewaySyncException.class);
		assertThat(warningCount(output)).isEqualTo(1);
		assertThat(output).contains("WARN");
		assertThat(output).contains("Gateway sync failed for role " + ROLE_ID + ": POST " + syncUrl
				+ " → status=206, body={\"success\":false,\"failedRoles\":[\"A\"]}");

		gatewayAnswers(200, "<html>not the gateway</html>");
		assertThatThrownBy(() -> client().sync(ROLE_ID)).isInstanceOf(GatewaySyncException.class);
		assertThat(warningCount(output)).isEqualTo(2);
		assertThat(output).contains("status=200, body=<html>not the gateway</html>");

		gatewayAnswersAfter(Duration.ofSeconds(3));
		assertThatThrownBy(() -> client().sync(ROLE_ID)).isInstanceOf(GatewaySyncException.class);
		assertThat(warningCount(output)).isEqualTo(3);
		assertThat(output).contains("POST " + syncUrl + " → status=none, body=none, error=no answer within 1s");

		String closedUrl = "http://localhost:" + closedPort();
		HttpGatewaySyncClient unreachable = new HttpGatewaySyncClient(new GatewayProperties(closedUrl),
				new ObjectMapper(), Duration.ofSeconds(1));
		assertThatThrownBy(() -> unreachable.sync(ROLE_ID)).isInstanceOf(GatewaySyncException.class);
		assertThat(warningCount(output)).isEqualTo(4);
		assertThat(output).contains("POST " + closedUrl + SYNC_PATH + " → status=none, body=none, error=could not connect");
	}

	@Test
	void successLogsNothing(CapturedOutput output) {
		gatewayAnswers(200, "{\"success\":true}");

		client().sync(ROLE_ID);

		assertThat(warningCount(output)).isZero();
	}

	// ---- helpers ----

	/** Client pointed at the fake gateway, with a 1s timeout so the timeout tests are quick. */
	private HttpGatewaySyncClient client() {
		GatewayProperties properties = new GatewayProperties(gateway.getRuntimeInfo().getHttpBaseUrl());
		return new HttpGatewaySyncClient(properties, new ObjectMapper(), Duration.ofSeconds(1));
	}

	private void gatewayAnswers(int status, String body) {
		gateway.stubFor(post(SYNC_PATH)
				.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
	}

	private void gatewayAnswersAfter(Duration delay) {
		gateway.stubFor(post(SYNC_PATH)
				.willReturn(aResponse().withStatus(200).withBody("{\"success\":true}")
						.withFixedDelay((int) delay.toMillis())));
	}

	private int warningCount(CapturedOutput output) {
		int count = 0;
		int from = output.getOut().indexOf("Gateway sync failed");
		while (from >= 0) {
			count = count + 1;
			from = output.getOut().indexOf("Gateway sync failed", from + 1);
		}
		return count;
	}

	/** A port nothing listens on: open one, note its number, close it again. */
	private int closedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
