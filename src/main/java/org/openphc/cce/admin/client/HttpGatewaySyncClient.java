package org.openphc.cce.admin.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.openphc.cce.admin.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GatewaySyncClient over HTTP. Only a 200 with "success": true counts as done; every other reply, and no reply
 * at all, becomes a GatewaySyncException with a reason and one WARN line with the details.
 */
@Component
public class HttpGatewaySyncClient implements GatewaySyncClient {

	private static final Logger log = LoggerFactory.getLogger(HttpGatewaySyncClient.class);

	// Longest wait for the gateway to connect, and again to answer
	static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String SYNC_PATH = "/api/permissions/sync-to-keycloak";

	private final String baseUrl;
	private final String url;
	private final Duration timeout;
	private final ObjectMapper objectMapper;
	private final RestClient restClient;

	// Two constructors, so Spring is told which one to use
	@Autowired
	public HttpGatewaySyncClient(GatewayProperties properties, ObjectMapper objectMapper) {
		this(properties, objectMapper, TIMEOUT);
	}

	/** Tests pass a shorter timeout. */
	HttpGatewaySyncClient(GatewayProperties properties, ObjectMapper objectMapper, Duration timeout) {
		this.baseUrl = properties.baseUrl();
		this.url = baseUrl + SYNC_PATH;
		this.timeout = timeout;
		this.objectMapper = objectMapper;

		// Java's own HTTP client: unlike the older HttpURLConnection, it never resends a POST by itself
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(timeout);
		// No Authorization header: the gateway's sync endpoint has no auth
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	@Override
	public void sync(UUID roleId) {
		Reply reply;
		try {
			// exchange hands over any status (206, 500, …) without throwing, so this class decides what it means
			reply = restClient.post().uri(url).exchange((request, response) -> readReply(response));
		} catch (ResourceAccessException e) {
			log.warn("Gateway sync failed for role {}: POST {} → status=none, body=none, error={}", roleId, url,
					noAnswerError(e));
			throw new GatewaySyncException(noAnswerReason(e));
		}

		JsonNode json = readJson(reply.body());
		if (reply.status() == 200 && isSuccess(json)) {
			return;
		}
		log.warn("Gateway sync failed for role {}: POST {} → status={}, body={}", roleId, url, reply.status(),
				reply.body());
		throw new GatewaySyncException(reasonFor(reply.status(), json));
	}

	private record Reply(int status, String body) {
	}

	private Reply readReply(ClientHttpResponse response) throws IOException {
		int status = response.getStatusCode().value();
		String body = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
		return new Reply(status, body);
	}

	/** Null when the body is empty or not JSON (e.g. an HTML page from something that is not the gateway). */
	private JsonNode readJson(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readTree(body);
		} catch (IOException notJson) {
			return null;
		}
	}

	private boolean isSuccess(JsonNode json) {
		if (json == null) {
			return false;
		}
		JsonNode success = json.get("success");
		if (success == null || !success.isBoolean()) {
			return false;
		}
		return success.booleanValue();
	}

	private String reasonFor(int status, JsonNode json) {
		if (status == 206) {
			List<String> failedRoles = failedRoles(json);
			if (!failedRoles.isEmpty()) {
				return "Gateway reloaded its rules, but Keycloak role sync failed for: " + String.join(", ", failedRoles)
						+ ".";
			}
		}
		String message = gatewayMessage(json);
		if (message == null) {
			return "Gateway sync error: HTTP " + status + ".";
		}
		return "Gateway sync error: " + endWithPeriod(message);
	}

	private List<String> failedRoles(JsonNode json) {
		List<String> names = new ArrayList<>();
		if (json == null) {
			return names;
		}
		JsonNode failed = json.get("failedRoles");
		if (failed == null || !failed.isArray()) {
			return names;
		}
		for (JsonNode name : failed) {
			names.add(name.asText());
		}
		return names;
	}

	private String gatewayMessage(JsonNode json) {
		if (json == null) {
			return null;
		}
		JsonNode message = json.get("message");
		if (message == null || !message.isTextual() || message.asText().isBlank()) {
			return null;
		}
		return message.asText();
	}

	private String endWithPeriod(String text) {
		if (text.endsWith(".")) {
			return text;
		}
		return text + ".";
	}

	private String noAnswerReason(ResourceAccessException e) {
		if (isTimeout(e)) {
			return "Gateway did not answer within " + timeout.toSeconds() + "s.";
		}
		return "Gateway not reachable at " + baseUrl + ".";
	}

	/**
	 * For the log. Java's HTTP client words these badly: a timeout shows as "Request cancelled", a refused
	 * connection as a ConnectException without a message.
	 */
	private String noAnswerError(ResourceAccessException e) {
		if (isTimeout(e)) {
			return "no answer within " + timeout.toSeconds() + "s";
		}
		if (hasCause(e, ConnectException.class)) {
			return "could not connect";
		}
		return rootMessage(e);
	}

	private boolean hasCause(Throwable e, Class<? extends Throwable> type) {
		Throwable cause = e;
		while (cause != null) {
			if (type.isInstance(cause)) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}

	private boolean isTimeout(Throwable e) {
		if (hasCause(e, HttpTimeoutException.class)) {
			return true;
		}
		return hasCause(e, SocketTimeoutException.class);
	}

	/** The innermost cause says what really happened, e.g. "Connection refused" or "request timed out". */
	private String rootMessage(Throwable e) {
		Throwable root = e;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		if (root.getMessage() == null) {
			return root.getClass().getSimpleName();
		}
		return root.getMessage();
	}
}
