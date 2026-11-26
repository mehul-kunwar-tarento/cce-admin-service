package org.openphc.cce.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.entity.Menu;
import org.openphc.cce.admin.entity.MenuType;
import org.openphc.cce.admin.entity.RoleMenu;
import org.openphc.cce.admin.entity.RoleMenuId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The menus and role_menus tables from Java, read against the seed.
 * Each test runs in a transaction that is rolled back, so tests never see each other's rows.
 */
// The whole app starts (security included), so the JWT, Keycloak and gateway settings need values
@SpringBootTest(properties = { "JWT_ISSUER=http://test-keycloak/realms/cce", "JWT_AUDIENCE=cce-admin-service",
		"KEYCLOAK_BASE_URL=http://test-keycloak", "KEYCLOAK_CLIENT_SECRET=test-secret",
		"GATEWAY_BASE_URL=http://test-gateway" })
@Testcontainers
@Transactional
class MenuRepositoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

	@Autowired
	private MenuRepository menuRepository;

	@Autowired
	private RoleMenuRepository roleMenuRepository;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void seedHasTwelveMenusTenOfThemNav() {
		List<Menu> menus = menuRepository.findAll();

		int navCount = 0;
		int detailCount = 0;
		for (Menu menu : menus) {
			if (menu.getType() == MenuType.NAV) {
				navCount++;
			}
			if (menu.getType() == MenuType.DETAIL) {
				detailCount++;
			}
		}
		assertThat(menus).hasSize(12);
		assertThat(navCount).isEqualTo(10);
		assertThat(detailCount).isEqualTo(2);
	}

	@Test
	void detailMenusPointAtTheirNavParent() {
		Menu compliance = menuWithKey("compliance");
		Menu patients = menuWithKey("patients");
		Menu protocolDetail = menuWithKey("protocol-detail");
		Menu patientDetail = menuWithKey("patient-detail");

		assertThat(protocolDetail.getParentId()).isEqualTo(compliance.getId());
		assertThat(patientDetail.getParentId()).isEqualTo(patients.getId());
		assertThat(compliance.getParentId()).isNull();
		assertThat(patientDetail.getSortOrder()).isEqualTo(61);
		assertThat(patientDetail.isActive()).isTrue();
	}

	@Test
	void roleMenuIsFoundByItsPair() {
		UUID superAdminId = jdbc.queryForObject("SELECT id FROM roles WHERE name = 'SUPER_ADMIN'", UUID.class);
		UUID dashboardId = menuWithKey("dashboard").getId();
		jdbc.update("INSERT INTO role_menus (role_id, menu_id) VALUES (?, ?)", superAdminId, dashboardId);

		Optional<RoleMenu> found = roleMenuRepository.findById(new RoleMenuId(superAdminId, dashboardId));

		assertThat(found).isPresent();
		assertThat(found.get().getRoleId()).isEqualTo(superAdminId);
		assertThat(found.get().getMenuId()).isEqualTo(dashboardId);
		assertThat(found.get().getCreatedAt()).isNotNull();
	}

	private Menu menuWithKey(String key) {
		for (Menu menu : menuRepository.findAll()) {
			if (menu.getKey().equals(key)) {
				return menu;
			}
		}
		throw new AssertionError("No seed menu with key " + key);
	}
}
