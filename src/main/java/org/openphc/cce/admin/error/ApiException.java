package org.openphc.cce.admin.error;

import org.springframework.http.HttpStatus;

/** An error raised on purpose. Its message is written for the caller and returned as it is. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final ErrorCode code;

	public ApiException(HttpStatus status, ErrorCode code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public ErrorCode getCode() {
		return code;
	}
}
