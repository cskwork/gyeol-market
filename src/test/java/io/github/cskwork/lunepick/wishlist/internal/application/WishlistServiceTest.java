package io.github.cskwork.lunepick.wishlist.internal.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.cskwork.lunepick.catalog.CatalogApi;
import io.github.cskwork.lunepick.shared.*;
import io.github.cskwork.lunepick.wishlist.internal.config.WishlistProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WishlistServiceTest {
    WishlistStore store = mock(WishlistStore.class);
    CatalogApi catalog = mock(CatalogApi.class);
    WishlistService service = new WishlistService(store, catalog, new WishlistProperties(500));

    @Test
    void listUsesOneBulkProjectionAndPaginatesOnlyVisibleProducts() {
        when(store.entries(1, 500))
                .thenReturn(
                        List.of(
                                new WishlistStore.Entry(10, 999),
                                new WishlistStore.Entry(9, 1),
                                new WishlistStore.Entry(8, 2)));
        var product =
                new CatalogApi.ProductListItem(
                        1,
                        "브랜드",
                        "상품",
                        new BigDecimal("9000"),
                        new BigDecimal("10000"),
                        BigDecimal.TEN,
                        "image",
                        BigDecimal.ZERO,
                        0);
        var second =
                new CatalogApi.ProductListItem(
                        2,
                        "브랜드",
                        "상품2",
                        BigDecimal.ONE,
                        BigDecimal.TEN,
                        BigDecimal.ZERO,
                        "image",
                        BigDecimal.ZERO,
                        0);
        when(catalog.summaries(List.of(999L, 1L, 2L))).thenReturn(List.of(product, second));
        var result = service.list(1, new PageQuery(0, 1));
        assertThat(result.items())
                .containsExactly(
                        new WishlistService.Item(
                                9,
                                1,
                                "상품",
                                "브랜드",
                                "image",
                                new BigDecimal("9000"),
                                new BigDecimal("10000"),
                                BigDecimal.TEN));
        assertThat(result.meta()).isEqualTo(new PageMeta(0, 1, 2));
        assertThat(service.list(1, new PageQuery(1, 1)).items())
                .extracting(WishlistService.Item::productId)
                .containsExactly(2L);
        assertThat(service.list(1, new PageQuery(2, 1)).items()).isEmpty();
        verify(catalog, never()).searchProduct(anyLong());
    }

    @Test
    void addRejectsMissingCatalogProductAndDelegatesDedupe() {
        when(catalog.searchProduct(1)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.add(2, 1))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("PRODUCT_NOT_FOUND"));
        verifyNoInteractions(store);
        when(catalog.searchProduct(1))
                .thenReturn(Optional.of(mock(CatalogApi.SearchProduct.class)));
        when(store.add(2, 1, 500)).thenReturn(true);
        service.add(2, 1);
        verify(store).add(2, 1, 500);
    }

    @Test
    void capReturnsAnActionableError() {
        when(catalog.searchProduct(1))
                .thenReturn(Optional.of(mock(CatalogApi.SearchProduct.class)));
        when(store.add(2, 1, 500)).thenReturn(false);
        assertThatThrownBy(() -> service.add(2, 1))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo("WISHLIST_LIMIT_REACHED");
                            assertThat(error.getMessage()).contains("500");
                        });
    }

    @Test
    void removeIsIdempotentAndMemberScoped() {
        service.remove(2, 1);
        service.remove(2, 1);
        verify(store, times(2)).remove(2, 1);
    }
}
