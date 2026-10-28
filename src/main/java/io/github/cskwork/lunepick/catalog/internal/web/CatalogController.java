package io.github.cskwork.lunepick.catalog.internal.web;

import io.github.cskwork.lunepick.catalog.CatalogApi.ProductListItem;
import io.github.cskwork.lunepick.catalog.internal.application.CatalogService;
import io.github.cskwork.lunepick.catalog.internal.application.CatalogViews.*;
import io.github.cskwork.lunepick.shared.ApiResponse;
import io.github.cskwork.lunepick.shared.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    @Operation(summary = "카테고리 트리 조회")
    public ApiResponse<CategoryTree> categories() {
        return ApiResponse.ok(service.categories());
    }

    @GetMapping("/categories/{categoryId}/products")
    @Operation(summary = "카테고리 상품 목록 조회")
    public ApiResponse<List<ProductListItem>> categoryProducts(
            @PathVariable String categoryId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        return response(service.categoryProducts(categoryId, sort, PageQuery.from(page, size, 20)));
    }

    @GetMapping("/products")
    @Operation(summary = "상품 목록 조회")
    public ApiResponse<List<ProductListItem>> products(
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String brandId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        return response(
                service.products(categoryId, brandId, sort, PageQuery.from(page, size, 20)));
    }

    @GetMapping({"/products/rankings", "/products/best-sellers"})
    @Operation(summary = "할인율 기준 상품 랭킹과 베스트셀러")
    public ApiResponse<List<ProductListItem>> rankings(
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String brandId,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        return response(
                service.products(categoryId, brandId, "POPULAR", PageQuery.from(page, size, 20)));
    }

    @GetMapping("/products/{productId:\\d+}")
    @Operation(summary = "상품 상세 조회")
    public ApiResponse<ProductDetail> detail(@PathVariable long productId) {
        return ApiResponse.ok(service.detail(productId));
    }

    @GetMapping("/products/{productId:\\d+}/reviews")
    @Operation(summary = "상품 리뷰 페이지 조회")
    public ApiResponse<List<Review>> reviews(
            @PathVariable long productId,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        return response(service.reviews(productId, PageQuery.from(page, size, 10)));
    }

    private <T> ApiResponse<List<T>> response(Page<T> page) {
        return ApiResponse.page(page.items(), page.meta());
    }
}
