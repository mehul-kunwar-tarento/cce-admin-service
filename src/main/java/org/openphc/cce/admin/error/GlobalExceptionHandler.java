package org.openphc.cce.admin.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception into {"error":{"code","message"}}, so Spring's default error body
 * never reaches a caller. Unplanned errors become 500 with a reference id that is also logged.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** Raised on purpose: status, code and message as given, no reference id. */
	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
		return build(e.getStatus(), e.getCode(), e.getMessage());
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleUnknownUrl(HttpServletRequest request) {
		String message = "No endpoint " + request.getMethod() + " " + request.getRequestURI() + ".";
		return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, message);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleWrongMethod(HttpRequestMethodNotSupportedException e) {
		String message = "Method " + e.getMethod() + " is not allowed for this URL.";
		return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED, message);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleNotJson(HttpMediaTypeNotSupportedException e) {
		MediaType contentType = e.getContentType();
		String message = "Content-Type is missing. Send JSON (Content-Type: application/json).";
		if (contentType != null) {
			// type/subtype only, without parameters such as ;charset=UTF-8
			String sent = contentType.getType() + "/" + contentType.getSubtype();
			message = "Content-Type " + sent + " is not supported. Send JSON (Content-Type: application/json).";
		}
		return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE, message);
	}

	/** Broken JSON, missing body, or a field of the wrong type. Jackson's own text is not returned: it names Java classes. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadableBody() {
		String message = "Request body is not valid JSON, or a field has the wrong type.";
		return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message);
	}

	/** Bean validation on a request body, e.g. @NotBlank. Names every failing field, sorted. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException e) {
		List<String> problems = new ArrayList<>();
		for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
			problems.add(fieldError.getField() + " " + fieldError.getDefaultMessage());
		}
		Collections.sort(problems);
		String message = "Invalid request: " + String.join("; ", problems) + ".";
		return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message);
	}

	/** Everything else. */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleEverythingElse(Exception e, HttpServletRequest request) {
		HttpStatusCode springClientStatus = springClientErrorStatus(e);
		if (springClientStatus != null) {
			String message = "The request could not be processed (HTTP " + springClientStatus.value() + ").";
			return build(springClientStatus, ErrorCode.VALIDATION_ERROR, message);
		}

		String reference = newReference();
		log.error("[ref={}] {} {} failed", reference, request.getMethod(), request.getRequestURI(), e);
		String message = "Something went wrong on our side. The change in this request was not saved. "
				+ "Try again; if it keeps failing, report reference " + reference + ".";
		return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, message);
	}

	/** The 4xx status a Spring MVC exception carries (e.g. 406), or null if it is not one. */
	private HttpStatusCode springClientErrorStatus(Exception e) {
		if (!(e instanceof org.springframework.web.ErrorResponse)) {
			return null;
		}
		org.springframework.web.ErrorResponse springError = (org.springframework.web.ErrorResponse) e;
		HttpStatusCode status = springError.getStatusCode();
		if (!status.is4xxClientError()) {
			return null;
		}
		return status;
	}

	/** 8 random hex characters, e.g. 7f3a9c2e. */
	private String newReference() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private ResponseEntity<ErrorResponse> build(HttpStatusCode status, ErrorCode code, String message) {
		return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
	}
}
