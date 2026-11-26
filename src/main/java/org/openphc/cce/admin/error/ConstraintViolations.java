package org.openphc.cce.admin.error;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/** Tells which database rule refused a row, so a service can answer that case with its own error. */
public final class ConstraintViolations {

	private ConstraintViolations() {
	}

	/** Whether the database refused the row because of this constraint (and not some other one). */
	public static boolean isViolationOf(DataIntegrityViolationException e, String constraintName) {
		if (!(e.getCause() instanceof ConstraintViolationException)) {
			return false;
		}
		ConstraintViolationException violation = (ConstraintViolationException) e.getCause();
		return constraintName.equals(violation.getConstraintName());
	}
}
