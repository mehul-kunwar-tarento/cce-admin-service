package org.openphc.cce.admin.bootstrap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.openphc.cce.admin.audit.AuditAction;
import org.openphc.cce.admin.audit.AuditWriter;
import org.openphc.cce.admin.client.KeycloakUser;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The database side of linking the first admin: the local user, its role and the audit row. */
@Service
public class BootstrapAdminService {

	/** The role the first admin must have in Keycloak and gets here; seeded by the migrations. */
	static final String SUPER_ADMIN = "SUPER_ADMIN";

	private final UserRepository userRepository;
	private final AuditWriter auditWriter;

	public BootstrapAdminService(UserRepository userRepository, AuditWriter auditWriter) {
		this.userRepository = userRepository;
		this.auditWriter = auditWriter;
	}

	/** Linked on an earlier start. */
	public boolean isLinked(String username) {
		return userRepository.existsByUsername(username);
	}

	/** Saves the users row, its SUPER_ADMIN role and the audit row, all or nothing. Returns the new users.id. */
	@Transactional
	public UUID link(KeycloakUser keycloakUser) {
		User user = userRepository.save(new User(keycloakUser.id(), keycloakUser.username(), keycloakUser.email(),
				keycloakUser.firstName(), keycloakUser.lastName(), UserStatus.ACTIVE));

		int added = userRepository.addRole(user.getId(), SUPER_ADMIN);
		if (added != 1) {
			throw new IllegalStateException("Role " + SUPER_ADMIN + " is missing from the roles table");
		}

		auditWriter.record(AuditAction.BOOTSTRAP_ADMIN_LINKED, "USER", user.getId().toString(), null,
				linkedDetails(keycloakUser));
		return user.getId();
	}

	private Map<String, Object> linkedDetails(KeycloakUser keycloakUser) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("keycloakId", keycloakUser.id());
		details.put("username", keycloakUser.username());
		details.put("email", keycloakUser.email());
		details.put("firstName", keycloakUser.firstName());
		details.put("lastName", keycloakUser.lastName());
		details.put("status", UserStatus.ACTIVE.name());
		details.put("roles", List.of(SUPER_ADMIN));
		return details;
	}
}
