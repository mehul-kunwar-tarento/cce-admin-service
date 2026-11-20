package org.openphc.cce.admin.api;

/** {page, pageSize, total} of a list response. page starts at 1. */
public record Pagination(int page, int pageSize, long total) {
}
