package org.openphc.cce.admin.health;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Asks the database if it is reachable. Used by the health endpoint; never throws. */
@Component
public class DatabaseCheck {

	private static final Logger log = LoggerFactory.getLogger(DatabaseCheck.class);
	private static final int VALID_TIMEOUT_SECONDS = 1;

	private final DataSource dataSource;

	public DatabaseCheck(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	public boolean isUp() {
		try (Connection connection = dataSource.getConnection()) {
			return connection.isValid(VALID_TIMEOUT_SECONDS);
		} catch (SQLException e) {
			log.debug("Database check failed: {}", e.getMessage());
			return false;
		}
	}
}
