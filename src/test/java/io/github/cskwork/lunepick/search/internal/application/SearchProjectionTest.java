package io.github.cskwork.lunepick.search.internal.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.cskwork.lunepick.catalog.CatalogApi;
import io.github.cskwork.lunepick.catalog.CatalogApi.SearchProduct;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SearchProjectionTest {
    private final CatalogApi catalog = mock(CatalogApi.class);
    private final SearchStore repository = mock(SearchStore.class);
    private final SearchProjection projection = new SearchProjection(catalog, repository);
    private final SearchProduct product =
            new SearchProduct(
                    1L,
                    2L,
                    "브랜드",
                    "크림",
                    "보습",
                    BigDecimal.TEN,
                    BigDecimal.TEN,
                    BigDecimal.ZERO,
                    "/images/cream.png",
                    List.of(1L),
                    1,
                    BigDecimal.ZERO,
                    0);

    @Test
    void emptyProjectionInitializesThroughPagedCatalogBoundary() {
        when(repository.isEmpty()).thenReturn(true);
        when(catalog.searchSnapshot(0, 250)).thenReturn(List.of(product));
        projection.rebuild();
        var order = inOrder(catalog, repository);
        order.verify(repository).lockForInitialization();
        order.verify(repository).isEmpty();
        order.verify(catalog).searchSnapshot(0, 250);
        order.verify(repository).upsert(product);
    }

    @Test
    void initializationReadsAnotherPageOnlyAfterTheLastProductId() {
        when(repository.isEmpty()).thenReturn(true);
        when(catalog.searchSnapshot(0, 250)).thenReturn(Collections.nCopies(250, product));
        when(catalog.searchSnapshot(1, 250)).thenReturn(List.of());
        projection.rebuild();
        verify(catalog).searchSnapshot(0, 250);
        verify(catalog).searchSnapshot(1, 250);
        verify(repository, Mockito.times(250)).upsert(product);
    }

    @Test
    void existingProjectionIsNeverReplacedOnStartup() {
        projection.rebuild();
        verify(repository).lockForInitialization();
        verify(repository).isEmpty();
        verifyNoInteractions(catalog);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void catalogSnapshotFailureDoesNotClearProjection() {
        when(repository.isEmpty()).thenReturn(true);
        when(catalog.searchSnapshot(0, 250))
                .thenThrow(new IllegalStateException("catalog unavailable"));
        assertThatThrownBy(projection::rebuild).isInstanceOf(IllegalStateException.class);
        verify(repository).lockForInitialization();
        verify(repository).isEmpty();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void changeReplacesExistingProductThroughPublicCatalogBoundary() {
        when(catalog.searchProduct(1L)).thenReturn(Optional.of(product));
        projection.update(1L, projection.product(1L));
        verify(repository).upsert(product);
    }

    @Test
    void missingProductRemovesStaleSearchDocument() {
        when(catalog.searchProduct(1L)).thenReturn(Optional.empty());
        projection.update(1L, projection.product(1L));
        verify(repository).remove(1L);
    }

    @Test
    void catalogReadFailureDoesNotTouchProjection() {
        when(catalog.searchProduct(1L)).thenThrow(new IllegalStateException("catalog unavailable"));
        assertThatThrownBy(() -> projection.product(1L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void projectionWriteFailureRemainsVisibleToWorker() {
        Mockito.doThrow(new IllegalStateException("projection unavailable"))
                .when(repository)
                .upsert(product);
        assertThatThrownBy(() -> projection.update(1L, Optional.of(product)))
                .isInstanceOf(IllegalStateException.class);
    }
}
