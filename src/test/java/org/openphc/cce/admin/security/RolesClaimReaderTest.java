package org.openphc.cce.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class RolesClaimReaderTest {

	private final RolesClaimReader defaultReader = new RolesClaimReader("realm_access.roles");

	@Test
	void defaultPathReadsRealmRoles() {
		Jwt token = tokenWithClaim("realm_access", Map.of("roles", List.of("SUPER_ADMIN", "default-roles-cce")));

		assertThat(defaultReader.read(token)).containsExactly("SUPER_ADMIN", "default-roles-cce");
	}

	@Test
	void configuredPathReadsClientRoles() {
		RolesClaimReader clientRolesReader = new RolesClaimReader("resource_access.gateway-service.roles");
		Jwt token = tokenWithClaim("resource_access",
				Map.of("gateway-service", Map.of("roles", List.of("COMPLIANCE_READ"))));

		assertThat(clientRolesReader.read(token)).containsExactly("COMPLIANCE_READ");
	}

	@Test
	void missingClaimGivesEmptyList() {
		Jwt token = tokenWithClaim("scope", "openid");

		assertThat(defaultReader.read(token)).isEmpty();
	}

	@Test
	void missingRolesInsideGivesEmptyList() {
		Jwt token = tokenWithClaim("realm_access", Map.of("other", List.of("X")));

		assertThat(defaultReader.read(token)).isEmpty();
	}

	@Test
	void rolesThatAreNotAListGiveEmptyList() {
		Jwt token = tokenWithClaim("realm_access", Map.of("roles", "SUPER_ADMIN"));

		assertThat(defaultReader.read(token)).isEmpty();
	}

	@Test
	void nonTextEntriesAreSkipped() {
		Jwt token = tokenWithClaim("realm_access", Map.of("roles", List.of("SUPER_ADMIN", 42)));

		assertThat(defaultReader.read(token)).containsExactly("SUPER_ADMIN");
	}

	/** A token with "sub" plus one extra claim; the signature doesn't matter here. */
	private Jwt tokenWithClaim(String name, Object value) {
		return Jwt.withTokenValue("test-token")
				.header("alg", "RS256")
				.subject("kc-user-1")
				.claim(name, value)
				.build();
	}
}
