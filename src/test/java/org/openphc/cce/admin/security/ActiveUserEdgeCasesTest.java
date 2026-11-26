package org.openphc.cce.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.health.DatabaseCheck;
import org.openphc.cce.admin.repository.ApiPermissionRepository;
import org.openphc.cce.admin.repository.AuditLogRepository;
import org.openphc.cce.admin.repository.MenuRepository;
import org.openphc.cce.admin.repository.RoleMenuRepository;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.persistence.EntityManager;

/**
 * Cases a real database can't easily show: roles read from another claim path, and the database
 * failing during the user lookup or the login-time update. The app runs without a database; the repository is a stand-in.
 */
@SpringBootTest(properties = {
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
		"JWT_ROLES_CLAIM_PATH=resource_access.gateway-service.roles" })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ActiveUserEdgeCasesTest {

	static TestTokens tokens = new TestTokens();

	@DynamicPropertySource
	static void jwtSettings(DynamicPropertyRegistry registry) {
		registry.add("JWT_ISSUER", () -> TestTokens.ISSUER);
		registry.add("JWT_AUDIENCE", () -> TestTokens.AUDIENCE);
		registry.add("KEYCLOAK_BASE_URL", () -> "http://test-keycloak");
		registry.add("KEYCLOAK_CLIENT_SECRET", () -> "test-secret");
		registry.add("GATEWAY_BASE_URL", () -> "http://test-gateway");
		registry.add("JWT_JWK_SET_URI", tokens::jwkSetUri);
	}

	@AfterAll
	static void stopJwksServer() {
		tokens.stop();
	}

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserRepository userRepository;

	// No database here; the audit writer needs its repository and a transaction manager to start, and these
	// tests write no audit rows
	@MockitoBean
	private AuditLogRepository auditLogRepository;

	@MockitoBean
	private MenuRepository menuRepository;

	@MockitoBean
	private RoleMenuRepository roleMenuRepository;

	@MockitoBean
	private RoleRepository roleRepository;

	@MockitoBean
	private ApiPermissionRepository apiPermissionRepository;

	@MockitoBean
	private UserRoleRepository userRoleRepository;

	@MockitoBean
	private PlatformTransactionManager transactionManager;

	// RoleService reads rows back through it; nothing here creates a role
	@MockitoBean
	private EntityManager entityManager;

	@MockitoBean
	private DatabaseCheck databaseCheck;

	@Test
	void rolesComeFromTheConfiguredPath() throws Exception {
		User asha = activeUser("asha");
		when(userRepository.findByKeycloakId("kc-asha")).thenReturn(Optional.of(asha));
		Map<String, Object> resourceAccess = Map.of("gateway-service", Map.of("roles", List.of("COMPLIANCE_READ")));

		mockMvc.perform(get("/test/current-user")
						.header("Authorization", "Bearer " + tokens.forUserWithClaim("kc-asha", "resource_access", resourceAccess)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roles").value(contains("COMPLIANCE_READ")));
	}

	@Test
	void databaseErrorIs500WithReferenceNot401() throws Exception {
		when(userRepository.findByKeycloakId("kc-asha"))
				.thenThrow(new DataAccessResourceFailureException("Connection refused"));

		MvcResult result = mockMvc.perform(get("/test/secure")
						.header("Authorization", "Bearer " + tokens.forUser("kc-asha")))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
				.andReturn();

		String message = JsonPath.read(result.getResponse().getContentAsString(), "$.error.message");
		assertThat(message).containsPattern("report reference [0-9a-f]{8}\\.");
		assertThat(message).doesNotContain("Connection refused");
	}

	@Test
	void failedLoginUpdateStillAnswers200(CapturedOutput output) throws Exception {
		User asha = activeUser("asha");
		when(userRepository.findByKeycloakId("kc-asha")).thenReturn(Optional.of(asha));
		when(userRepository.moveLastLoginForward(any(), any()))
				.thenThrow(new DataAccessResourceFailureException("Connection refused"));

		mockMvc.perform(get("/test/secure")
						.header("Authorization", "Bearer " + tokens.forUserWithClaim("kc-asha", "auth_time", 1795400100L)))
				.andExpect(status().isOk());

		assertThat(output.getOut()).contains("WARN").contains("Could not update last_login_at");
	}

	/** A stand-in ACTIVE user; only the getters the filter reads are filled. */
	private User activeUser(String username) {
		User user = mock(User.class);
		when(user.getId()).thenReturn(UUID.randomUUID());
		when(user.getUsername()).thenReturn(username);
		when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
		return user;
	}
}
