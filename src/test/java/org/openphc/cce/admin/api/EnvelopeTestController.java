package org.openphc.cce.admin.api;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints for the success envelope and paging, before real endpoints exist. */
@RestController
class EnvelopeTestController {

	static final long TOTAL = 42;

	record Item(String name) {
	}

	@GetMapping("/test/item")
	ApiResponse<Item> item() {
		return ApiResponse.of(new Item("one"));
	}

	@GetMapping("/test/items")
	ApiResponse<List<Item>> items(@Valid PageParams params) {
		List<Item> items = new ArrayList<>();
		items.add(new Item("first on page " + params.page()));
		return ApiResponse.page(items, params, TOTAL);
	}
}
