import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Local stand-in for gateway-service's sync endpoint, for demos without the real gateway.
 * Answers every POST /api/permissions/sync-to-keycloak with success, but syncs nothing: Keycloak gets no
 * realm roles from it. Never used on a server, where GATEWAY_BASE_URL points at the real gateway.
 *
 * Run from the repo root:   java local/FakeGateway.java          (stop with Ctrl+C)
 */
public class FakeGateway {

	private static final int PORT = 8095;
	private static final String SYNC_PATH = "/api/permissions/sync-to-keycloak";
	private static final String SUCCESS = "{\"success\":true,\"message\":\"Stand-in gateway: nothing was synced.\"}";

	public static void main(String[] args) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
		server.createContext(SYNC_PATH, FakeGateway::answer);
		server.start();
		System.out.println("Stand-in gateway on http://localhost:" + PORT + SYNC_PATH + " (Ctrl+C to stop)");
	}

	private static void answer(HttpExchange exchange) throws IOException {
		if (!exchange.getRequestMethod().equals("POST")) {
			System.out.println(LocalTime.now().withNano(0) + "  " + exchange.getRequestMethod() + " → 405");
			exchange.sendResponseHeaders(405, -1);
			exchange.close();
			return;
		}
		System.out.println(LocalTime.now().withNano(0) + "  sync called → 200 success");
		byte[] body = SUCCESS.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}
}
