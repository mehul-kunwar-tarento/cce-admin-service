package org.openphc.cce.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openphc.cce.admin.repository.AuditLogRepository;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A failure record when the database itself fails: repository and transaction manager are stand-ins that
 * throw, so "the caller never sees an exception" can be checked for both the insert and the commit.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuditWriterFailureTest {

	private final AuditLogRepository repository = mock(AuditLogRepository.class);
	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
	private final AuditWriter auditWriter = new AuditWriter(repository, new ObjectMapper(), transactionManager);

	@Test
	void saveFailureNeverReachesTheCaller(CapturedOutput output) {
		transactionStarts();
		when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("database is down"));

		assertThatCode(this::recordCreateFailure).doesNotThrowAnyException();

		assertThatTheLogHasEveryDetail(output);
		assertThat(output).contains("Audit row for USER_CREATE_FAILED USER asha not written");
		assertThat(output).contains("database is down");
	}

	@Test
	void commitFailureNeverReachesTheCaller(CapturedOutput output) {
		TransactionStatus status = transactionStarts();
		// The insert itself goes through; the database fails when the transaction is committed
		doThrow(new TransactionSystemException("commit failed")).when(transactionManager).commit(status);

		assertThatCode(this::recordCreateFailure).doesNotThrowAnyException();

		assertThatTheLogHasEveryDetail(output);
		assertThat(output).contains("Audit row for USER_CREATE_FAILED USER asha not written");
		assertThat(output).contains("commit failed");
	}

	@Test
	void detailsAreLoggedEvenWhenTheRowIsWritten(CapturedOutput output) {
		transactionStarts();

		recordCreateFailure();

		assertThatTheLogHasEveryDetail(output);
		assertThat(output).doesNotContain("not written");
	}

	private TransactionStatus transactionStarts() {
		TransactionStatus status = new SimpleTransactionStatus();
		when(transactionManager.getTransaction(any())).thenReturn(status);
		return status;
	}

	private void recordCreateFailure() {
		auditWriter.recordFailure(AuditAction.USER_CREATE_FAILED, "USER", "asha", "kc-123", "save user",
				"duplicate email", "keycloak user deleted");
	}

	private void assertThatTheLogHasEveryDetail(CapturedOutput output) {
		assertThat(output).contains("ERROR");
		assertThat(output).contains("USER_CREATE_FAILED USER asha: keycloakId=kc-123, failedStep=save user,"
				+ " error=duplicate email, cleanup=keycloak user deleted");
	}
}
