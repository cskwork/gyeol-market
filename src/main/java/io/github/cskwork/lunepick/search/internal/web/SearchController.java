package io.github.cskwork.lunepick.search.internal.web;

import io.github.cskwork.lunepick.catalog.CatalogApi.ProductListItem;
import io.github.cskwork.lunepick.search.internal.application.SearchService;
import io.github.cskwork.lunepick.search.internal.domain.SearchQuery;
import io.github.cskwork.lunepick.shared.ApiResponse;
import io.github.cskwork.lunepick.shared.PageMeta;
import io.github.cskwork.lunepick.shared.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
class SearchController {
    private final SearchService service;

    SearchController(SearchService service) {
        this.service = service;
    }

    @GetMapping("/products")
    @Operation(summary = "카탈로그 전문 및 부분 문자열 검색")
    ApiResponse<List<ProductListItem>> products(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String brandId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        // brandId is intentionally ignored, matching mockApi.ts despite SearchProductsParams
        // advertising it.
        SearchQuery.positiveId(brandId, "Brand ID");
        PageQuery pagination = PageQuery.from(page, size, 20);
        var result =
                service.search(keyword, categoryId, sort, pagination.limit(), pagination.offset());
        return ApiResponse.page(
                result.products(),
                new PageMeta(pagination.page(), pagination.size(), result.total()));
    }

    @GetMapping("/autocomplete")
    @Operation(summary = "상품명 자동완성")
    ApiResponse<SearchService.AutocompleteResponse> autocomplete(
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String size) {
        return ApiResponse.ok(service.autocomplete(prefix, size));
    }

    @GetMapping("/popular")
    @Operation(summary = "인기 검색어 조회")
    ApiResponse<SearchService.PopularResponse> popular() {
        return ApiResponse.ok(service.popular());
    }
}
