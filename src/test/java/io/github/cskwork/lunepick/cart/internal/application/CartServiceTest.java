package io.github.cskwork.lunepick.cart.internal.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.cskwork.lunepick.catalog.CatalogApi;
import io.github.cskwork.lunepick.catalog.CatalogStockApi;
import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CartServiceTest {
    CartStore store = mock(CartStore.class);
    CatalogStockApi catalog = mock(CatalogStockApi.class);
    CatalogApi products = mock(CatalogApi.class);
    CartService service = new CartService(store, catalog, products);
    CartStore.Line line = new CartStore.Line(10, 101, 2);

    private CatalogStockApi.OptionSnapshot option(String status) {
        return new CatalogStockApi.OptionSnapshot(
                101, 1, "상품", "옵션", new BigDecimal("12500"), status, null);
    }

    @Test
    void viewEnrichesAllLinesInOneBulkReadAndSkipsDeletedOptions() {
        when(store.lines(1)).thenReturn(List.of(line, new CartStore.Line(11, 999, 5)));
        when(catalog.options(List.of(101L, 999L))).thenReturn(List.of(option("ON_SALE")));
        when(products.summaries(List.of(1L)))
                .thenReturn(
                        List.of(
                                new CatalogApi.ProductListItem(
                                        1,
                                        "브랜드",
                                        "상품",
                                        new BigDecimal("12500"),
                                        new BigDecimal("12500"),
                                        BigDecimal.ZERO,
                                        "/images/product.png",
                                        BigDecimal.ZERO,
                                        0)));
        var result = service.view(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().onSale()).isTrue();
        assertThat(result.items().getFirst().productId()).isEqualTo(1);
        assertThat(result.items().getFirst().thumbnailUrl()).isEqualTo("/images/product.png");
        verify(products).summaries(List.of(1L));
        assertThat(result.items().getFirst().lineSubtotal()).isEqualByComparingTo("25000");
        assertThat(result.totalItemCount()).isEqualTo(2);
        assertThat(result.totalAmount()).isEqualByComparingTo("25000");
        verify(catalog, never()).option(anyLong());
        verify(catalog).options(List.of(101L, 999L));
    }

    @Test
    void emptyAndStoppedCartViewsRemainExplicit() {
        when(store.lines(1)).thenReturn(List.of());
        when(catalog.options(List.of())).thenReturn(List.of());
        assertThat(service.view(1).totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        when(store.lines(1)).thenReturn(List.of(line));
        when(catalog.options(List.of(101L))).thenReturn(List.of(option("STOPPED")));
        var stopped = service.view(1).items().getFirst();
        assertThat(stopped.onSale()).isFalse();
        assertThat(stopped.productId()).isEqualTo(1);
        assertThat(stopped.thumbnailUrl()).isNull();
    }

    @Test
    void addReturnsAtomicStoreResultAndValidatesInteger() {
        when(catalog.option(101)).thenReturn(Optional.of(option("ON_SALE")));
        when(store.add(1, 101, 99)).thenReturn(new CartStore.Line(10, 101, 99));
        assertThat(service.add(1, 101, new BigDecimal("99")))
                .isEqualTo(new CartViews.Added(10, 99));
        assertThatThrownBy(() -> service.add(1, 101, new BigDecimal("1.5")))
                .isInstanceOf(ApiException.class);
        verify(store, times(1)).add(anyLong(), anyLong(), anyInt());
    }

    @Test
    void missingOptionIs404AndDoesNotWrite() {
        when(catalog.option(999)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.add(1, 999, BigDecimal.ONE))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("PRODUCT_OPTION_NOT_FOUND"));
        verifyNoInteractions(store);
    }

    @Test
    void absoluteUpdateIsOneOwnedWriteWithoutReadOrRetry() {
        when(store.update(1, 10, 5)).thenReturn(true);
        service.update(1, 10, new BigDecimal("5"));
        verify(store).update(1, 10, 5);
        verifyNoMoreInteractions(store);
    }

    @Test
    void missingOrForeignLineUpdatesAndDeletesAre404() {
        assertThatThrownBy(() -> service.update(2, 10, BigDecimal.ONE))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(() -> service.remove(2, 10)).isInstanceOf(ApiException.class);
        when(store.delete(1, 10)).thenReturn(true);
        service.remove(1, 10);
    }

    @Test
    void mergeIsEmptyAndDoesNotStoreOrReadGuestState() {
        assertThat(service.merge(1, "session").mergedItemCount()).isZero();
        assertThat(service.merge(1, "session").mergedItemCount()).isZero();
        for (String session : new String[] {null, " ", "x".repeat(513)}) {
            assertThatThrownBy(() -> service.merge(1, session)).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(store, catalog);
    }

    @Test
    void checkoutSnapshotAndCleanupStayMemberScoped() {
        when(store.lines(1)).thenReturn(List.of(line, new CartStore.Line(12, 102, 1)));
        assertThat(service.selectedItemIds(1, List.of(101L))).containsExactly(10L);
        service.clearPurchased(1, List.of(10L));
        verify(store).delete(1, 10);
        verify(store, never()).delete(1, 12);
    }
}
