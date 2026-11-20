package org.openphc.cce.admin.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

class DatabaseCheckTest {

	private final DataSource dataSource = mock(DataSource.class);
	private final Connection connection = mock(Connection.class);
	private final DatabaseCheck databaseCheck = new DatabaseCheck(dataSource);

	@Test
	void upWhenConnectionIsValid() throws SQLException {
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.isValid(1)).thenReturn(true);

		assertThat(databaseCheck.isUp()).isTrue();
	}

	@Test
	void downWhenNoConnectionCanBeHad() throws SQLException {
		when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused"));

		assertThat(databaseCheck.isUp()).isFalse();
	}

	@Test
	void downWhenConnectionIsNotValid() throws SQLException {
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.isValid(1)).thenReturn(false);

		assertThat(databaseCheck.isUp()).isFalse();
	}
}
