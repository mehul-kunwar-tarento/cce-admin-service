package org.openphc.cce.admin.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Reads the caller's roles from the token, at a dot path such as "realm_access.roles".
 * A missing claim gives an empty list; it never rejects the request.
 */
@Component
public class RolesClaimReader {

	private final String[] pathParts;

	public RolesClaimReader(@Value("${cce.jwt.roles-claim-path}") String claimPath) {
		this.pathParts = claimPath.split("\\.");
	}

	public List<String> read(Jwt token) {
		Object current = token.getClaims();
		for (String part : pathParts) {
			if (!(current instanceof Map<?, ?> map)) {
				return List.of();
			}
			current = map.get(part);
		}
		return onlyStrings(current);
	}

	/** The list at the end of the path, keeping only text entries; anything else gives an empty list. */
	private List<String> onlyStrings(Object value) {
		List<String> roles = new ArrayList<>();
		if (!(value instanceof Collection<?> items)) {
			return roles;
		}
		for (Object item : items) {
			if (item instanceof String role) {
				roles.add(role);
			}
		}
		return roles;
	}
}
