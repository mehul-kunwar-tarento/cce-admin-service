package org.openphc.cce.admin.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.openphc.cce.admin.api.PageParams;
import org.openphc.cce.admin.dto.UserResponse;
import org.openphc.cce.admin.dto.UserRoleItem;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.error.ApiException;
import org.openphc.cce.admin.error.ErrorCode;
import org.openphc.cce.admin.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

/** Users: one page with search and filters, and one user. */
@Service
public class UserQueryService {

	private final UserRepository userRepository;
	private final EntityManager entityManager;

	public UserQueryService(UserRepository userRepository, EntityManager entityManager) {
		this.userRepository = userRepository;
		this.entityManager = entityManager;
	}

	/** One page of users, A to Z by username, matching every filter given; a filter left out matches all. */
	@Transactional(readOnly = true)
	public Page<UserResponse> list(String q, UserStatus status, UUID roleId, String facilityScope,
			PageParams params) {
		// The same conditions serve the page and the count
		StringBuilder where = new StringBuilder(" WHERE 1 = 1");
		Map<String, Object> values = new HashMap<>();

		String search = trimToNull(q);
		if (search != null) {
			where.append(" AND (lower(u.username) LIKE :q ESCAPE '!' OR lower(u.firstName) LIKE :q ESCAPE '!'"
					+ " OR lower(u.lastName) LIKE :q ESCAPE '!' OR lower(u.email) LIKE :q ESCAPE '!')");
			values.put("q", "%" + escapeLike(search.toLowerCase(Locale.ROOT)) + "%");
		}
		if (status != null) {
			where.append(" AND u.status = :status");
			values.put("status", status);
		}
		if (roleId != null) {
			where.append(" AND EXISTS (SELECT 1 FROM UserRole ur WHERE ur.userId = u.id AND ur.roleId = :roleId)");
			values.put("roleId", roleId);
		}
		if (facilityScope != null) {
			where.append(" AND u.facilityScope = :facilityScope");
			values.put("facilityScope", facilityScope);
		}

		TypedQuery<Long> countQuery = entityManager.createQuery("SELECT count(u) FROM User u" + where, Long.class);
		TypedQuery<User> pageQuery = entityManager.createQuery(
				"SELECT u FROM User u" + where + " ORDER BY u.username", User.class);
		for (Map.Entry<String, Object> value : values.entrySet()) {
			countQuery.setParameter(value.getKey(), value.getValue());
			pageQuery.setParameter(value.getKey(), value.getValue());
		}
		// Pages are counted from 1 in our API
		pageQuery.setFirstResult((params.page() - 1) * params.pageSize());
		pageQuery.setMaxResults(params.pageSize());

		List<User> users = pageQuery.getResultList();
		long total = countQuery.getSingleResult();
		Map<UUID, List<UserRoleItem>> roles = rolesOf(users);

		List<UserResponse> items = new ArrayList<>();
		for (User user : users) {
			items.add(UserResponse.of(user, roles.get(user.getId())));
		}
		return new PageImpl<>(items, PageRequest.of(params.page() - 1, params.pageSize()), total);
	}

	@Transactional(readOnly = true)
	public UserResponse get(UUID id) {
		Optional<User> found = userRepository.findById(id);
		if (found.isEmpty()) {
			throw notFound(id);
		}
		User user = found.get();
		List<User> one = new ArrayList<>();
		one.add(user);
		return UserResponse.of(user, rolesOf(one).get(user.getId()));
	}

	/** The roles of all these users in one query; each user's list A to Z by name, empty if none. */
	private Map<UUID, List<UserRoleItem>> rolesOf(List<User> users) {
		Map<UUID, List<UserRoleItem>> roles = new HashMap<>();
		List<UUID> ids = new ArrayList<>();
		for (User user : users) {
			roles.put(user.getId(), new ArrayList<>());
			ids.add(user.getId());
		}
		if (ids.isEmpty()) {
			return roles;
		}

		List<Object[]> rows = entityManager.createQuery("SELECT ur.userId, r FROM UserRole ur, Role r"
				+ " WHERE r.id = ur.roleId AND ur.userId IN :ids ORDER BY r.name", Object[].class)
				.setParameter("ids", ids)
				.getResultList();
		for (Object[] row : rows) {
			UUID userId = (UUID) row[0];
			Role role = (Role) row[1];
			roles.get(userId).add(UserRoleItem.of(role));
		}
		return roles;
	}

	/** In LIKE, % and _ are wildcards and ! is our escape mark; a ! before each makes them plain characters. */
	private String escapeLike(String text) {
		return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}

	/** A blank search means no search. */
	private String trimToNull(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		return text.trim();
	}

	// Also used by the services that change a user, so all give the same text
	static ApiException notFound(UUID id) {
		return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.USER_NOT_FOUND, "No user with id " + id + ".");
	}
}
