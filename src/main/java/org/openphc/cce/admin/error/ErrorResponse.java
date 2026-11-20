package org.openphc.cce.admin.error;

/** Body of every error response: {"error":{"code":…,"message":…}}. */
public record ErrorResponse(ErrorDetail error) {

	public record ErrorDetail(String code, String message) {
	}

	public static ErrorResponse of(ErrorCode code, String message) {
		ErrorDetail detail = new ErrorDetail(code.name(), message);
		return new ErrorResponse(detail);
	}
}
