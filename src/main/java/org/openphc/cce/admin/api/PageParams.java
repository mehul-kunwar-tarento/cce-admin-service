package org.openphc.cce.admin.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * ?page=&pageSize= of a list endpoint. page starts at 1 (default 1); pageSize defaults to 20, max 100.
 * Out of range is rejected with 400, never silently capped.
 */
public record PageParams(
		@Min(1) Integer page,
		@Min(1) @Max(100) Integer pageSize) {

	public static final int DEFAULT_PAGE = 1;
	public static final int DEFAULT_PAGE_SIZE = 20;

	public PageParams {
		if (page == null) {
			page = DEFAULT_PAGE;
		}
		if (pageSize == null) {
			pageSize = DEFAULT_PAGE_SIZE;
		}
	}
}
