package io.github.cskwork.lunepick.catalog.internal.web;

import static io.github.cskwork.lunepick.catalog.internal.application.CatalogViews.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cskwork.lunepick.catalog.CatalogApi.ProductListItem;
import io.github.cskwork.lunepick.catalog.internal.application.CatalogService;
import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.PageMeta;
import io.github.cskwork.lunepick.shared.PageQuery;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CatalogController.class)
@AutoConfigureMockMvc(addFilters = false)
class CatalogControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CatalogService service;

    @Test
    void categoryTreeUsesTheStorefrontEnvelope() throws Exception {
        when(service.categories())
                .thenReturn(
                        new CategoryTree(
                                List.of(new CategoryNode(1, "스킨케어", "skincare", List.of()))));
        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.categories[0].id").value(1))
                .andExpect(jsonPath("$.data.categories[0].children").isEmpty());
    }

    @Test
    void listingPassesIntegerPaginationAndOptionalFilters() throws Exception {
        when(service.products(eq("2"), eq("4"), eq("PRICE_ASC"), eq(new PageQuery(0, 20))))
                .thenReturn(new Page<>(List.of(item()), new PageMeta(0, 20, 1)));
        mvc.perform(
                        get("/api/products")
                                .param("categoryId", "2")
                                .param("brandId", "4")
                                .param("sort", "PRICE_ASC")
                                .param("page", "0")
                                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].productId").value(1))
                .andExpect(jsonPath("$.data[0].salePrice").value(20000))
                .andExpect(jsonPath("$.data[0].rating").value(0))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.total").value(1));
    }

    @Test
    void categoryListingUsesDefaultLatestSortAndPageSize() throws Exception {
        when(service.categoryProducts(eq("2"), isNull(), eq(new PageQuery(0, 20))))
                .thenReturn(new Page<>(List.of(), new PageMeta(0, 20, 0)));
        mvc.perform(get("/api/categories/2/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.size").value(20));
    }

    @Test
    void rankingAndBestSellerRoutesAlwaysUsePopularity() throws Exception {
        when(service.products(isNull(), isNull(), eq("POPULAR"), eq(new PageQuery(0, 20))))
                .thenReturn(new Page<>(List.of(item()), new PageMeta(0, 20, 1)));
        for (String path : List.of("/api/products/rankings", "/api/products/best-sellers")) {
            mvc.perform(get(path).param("sort", "PRICE_ASC"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].productId").value(1));
        }
        verify(service, times(2)).products(null, null, "POPULAR", new PageQuery(0, 20));
    }

    @Test
    void reviewsHaveTheirOwnDefaultSizeAndEmptySeedData() throws Exception {
        when(service.reviews(1, new PageQuery(0, 10)))
                .thenReturn(new Page<>(List.of(), new PageMeta(0, 10, 0)));
        mvc.perform(get("/api/products/1/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.size").value(10))
                .andExpect(jsonPath("$.meta.total").value(0));
    }

    @Test
    void detailRendersOptionNullQuantityAndThumbnailFlag() throws Exception {
        when(service.detail(1))
                .thenReturn(
                        new ProductDetail(
                                1,
                                "새봄담",
                                "선크림",
                                new BigDecimal("20000"),
                                new BigDecimal("25000"),
                                new BigDecimal("20.0"),
                                "/thumbnail.png",
                                BigDecimal.ZERO,
                                0,
                                null,
                                "description",
                                List.of(new Option(1, "50ml", BigDecimal.ZERO, "ON_SALE", null)),
                                List.of(new Image(10, "/thumbnail.png", 1, true)),
                                List.of(new CategoryPath(1, "스킨케어", "skincare"))));
        mvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options[0].status").value("ON_SALE"))
                .andExpect(jsonPath("$.data.options[0].availableQuantity").doesNotExist())
                .andExpect(jsonPath("$.data.images[0].isThumbnail").value(true))
                .andExpect(jsonPath("$.data.categories[0].categorySlug").value("skincare"));
    }

    @Test
    void missingProductKeepsItsErrorCodeAndHttpStatus() throws Exception {
        when(service.detail(99))
                .thenThrow(new ApiException(404, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."));
        mvc.perform(get("/api/products/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void malformedPaginationReturns400BeforeReadingCatalog() throws Exception {
        for (String value : List.of("bad", "1.5", "Infinity", "0x2", "-1")) {
            mvc.perform(get("/api/products").param("page", value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
        }
        mvc.perform(get("/api/products/1/reviews").param("size", "101"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private ProductListItem item() {
        return new ProductListItem(
                1,
                "새봄담",
                "선크림",
                new BigDecimal("20000"),
                new BigDecimal("25000"),
                new BigDecimal("20.0"),
                "/thumbnail.png",
                BigDecimal.ZERO,
                0);
    }
}
