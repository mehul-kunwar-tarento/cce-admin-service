package org.openphc.cce.admin.security;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Signs test tokens with a key made for the test, and serves the matching public key at a JWKS URL
 * the way Keycloak does. The app then checks test tokens exactly as it checks real ones.
 */
public class TestTokens {

	public static final String ISSUER = "http://test-keycloak/realms/cce";
	public static final String AUDIENCE = "cce-admin-service";
	private static final String KEY_ID = "test-key";

	/** Ours: its public half is served at the JWKS URL. */
	private final RSAKey key;
	/** A stranger's key with the same id: tokens it signs must fail the signature check. */
	private final RSAKey otherKey;
	private final HttpServer server;

	public TestTokens() {
		key = newKey();
		otherKey = newKey();
		server = startJwksServer();
	}

	public String jwkSetUri() {
		return "http://localhost:" + server.getAddress().getPort() + "/certs";
	}

	public void stop() {
		server.stop(0);
	}

	/** Passes every check: our key, right issuer and audience, valid for 5 minutes. */
	public String valid() {
		return sign(claims().build(), key);
	}

	/** Expired 10 minutes ago, well past the 60 seconds Spring allows for clock drift. */
	public String expired() {
		Instant tenMinutesAgo = Instant.now().minusSeconds(600);
		JWTClaimsSet claims = claims()
				.issueTime(Date.from(tenMinutesAgo.minusSeconds(300)))
				.expirationTime(Date.from(tenMinutesAgo))
				.build();
		return sign(claims, key);
	}

	public String withIssuer(String issuer) {
		return sign(claims().issuer(issuer).build(), key);
	}

	public String withAudience(String audience) {
		return sign(claims().audience(audience).build(), key);
	}

	/** Looks right, but the signature is not from the key the app trusts. */
	public String signedByAnotherKey() {
		return sign(claims().build(), otherKey);
	}

	private JWTClaimsSet.Builder claims() {
		Instant now = Instant.now();
		return new JWTClaimsSet.Builder()
				.subject("kc-user-1")
				.issuer(ISSUER)
				.audience(List.of(AUDIENCE, "account"))
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plusSeconds(300)));
	}

	private String sign(JWTClaimsSet claims, RSAKey signingKey) {
		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build();
		SignedJWT token = new SignedJWT(header, claims);
		try {
			token.sign(new RSASSASigner(signingKey));
		} catch (JOSEException e) {
			throw new IllegalStateException("Could not sign test token", e);
		}
		return token.serialize();
	}

	private static RSAKey newKey() {
		try {
			return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
		} catch (JOSEException e) {
			throw new IllegalStateException("Could not make test key", e);
		}
	}

	/** A tiny web server on a free port that answers GET /certs with our public key. */
	private HttpServer startJwksServer() {
		String jwks = new JWKSet(key.toPublicJWK()).toString();
		try {
			HttpServer jwksServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			jwksServer.createContext("/certs", exchange -> sendJson(exchange, jwks));
			jwksServer.start();
			return jwksServer;
		} catch (IOException e) {
			throw new IllegalStateException("Could not start JWKS server", e);
		}
	}

	private static void sendJson(HttpExchange exchange, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}
}
