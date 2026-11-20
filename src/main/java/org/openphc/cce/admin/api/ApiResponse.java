package org.openphc.cce.admin.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Body of every successful response: {data} for one item, {data, pagination} for a list. */
public record ApiResponse<T>(
		T data,
		@JsonInclude(JsonInclude.Include.NON_NULL) Pagination pagination) {

	/** One item: {"data": …}, no pagination key. */
	public static <T> ApiResponse<T> of(T data) {
		return new ApiResponse<>(data, null);
	}

	/** One page of a list: {"data": […], "pagination": {…}}. */
	public static <T> ApiResponse<List<T>> page(List<T> items, PageParams params, long total) {
		Pagination pagination = new Pagination(params.page(), params.pageSize(), total);
		return new ApiResponse<>(items, pagination);
	}
}
