package io.github.cskwork.lunepick.catalog.internal.domain;

import static org.assertj.core.api.Assertions.*;

import io.github.cskwork.lunepick.catalog.CatalogApi;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CatalogProductTest {
    @ParameterizedTest
    @CsvSource({"22000,14900,32.3", "25000,20000,20.0", "0,0,0", "2000,1999,0.1"})
    void discountsUseDecimalRoundingForNonnegativePrices(
            String base, String sale, String expected) {
        assertThat(product(1, base, sale, 1).discountRate()).isEqualByComparingTo(expected);
    }

    @Test
    void sortsLikeStableFixtureSortIncludingUnknownRatingSort() {
        var first = product(1, "100", "80", 1);
        var second = product(2, "100", "80", 2);
        var third = product(3, "100", "50", 3);
        List<CatalogProduct> input = List.of(first, second, third);
        assertThat(ids(input, null)).containsExactly(3L, 2L, 1L);
        assertThat(ids(input, "LATEST")).containsExactly(3L, 2L, 1L);
        assertThat(ids(input, "PRICE_ASC")).containsExactly(3L, 1L, 2L);
        assertThat(ids(input, "PRICE_DESC")).containsExactly(1L, 2L, 3L);
        assertThat(ids(input, "POPULAR")).containsExactly(3L, 1L, 2L);
        assertThat(ids(input, "RATING")).containsExactly(1L, 2L, 3L);
        assertThat(ids(input, "anything")).containsExactly(1L, 2L, 3L);
    }

    @Test
    void snapshotsAreImmutableAndListItemHasSeedReviewValues() {
        List<Long> ids = new ArrayList<>(List.of(1L));
        var snapshot =
                new CatalogApi.SearchProduct(
                        1,
                        2,
                        "brand",
                        "product",
                        "description",
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        BigDecimal.ZERO,
                        null,
                        ids,
                        1,
                        BigDecimal.ZERO,
                        0);
        ids.add(2L);
        assertThat(snapshot.categoryIds()).containsExactly(1L);
        assertThatThrownBy(() -> snapshot.categoryIds().add(3L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(snapshot.toListItem().rating()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(snapshot.toListItem().reviewCount()).isZero();
    }

    @Test
    void productProjectionsPreserveActualRatingAndCatalogFields() {
        var product = product(2, "100", "80", 9);
        assertThat(product.searchProduct())
                .isEqualTo(
                        new CatalogApi.SearchProduct(
                                2,
                                1,
                                "brand",
                                "name",
                                "description",
                                new BigDecimal("80"),
                                new BigDecimal("100"),
                                new BigDecimal("20.0"),
                                "/thumbnail.png",
                                List.of(1L),
                                9,
                                new BigDecimal("4.5"),
                                3));
        assertThat(product.searchProduct().toListItem().rating()).isEqualByComparingTo("4.5");
        assertThat(product.searchProduct().toListItem().reviewCount()).isEqualTo(3);
        assertThat(product.listItem().rating()).isEqualByComparingTo("4.5");
        assertThat(product.listItem().reviewCount()).isEqualTo(3);
    }

    private List<Long> ids(List<CatalogProduct> products, String sort) {
        return products.stream().sorted(CatalogProduct.sort(sort)).map(CatalogProduct::id).toList();
    }

    private CatalogProduct product(long id, String base, String sale, long created) {
        return new CatalogProduct(
                id,
                1,
                "brand",
                null,
                "name",
                "description",
                new BigDecimal(base),
                new BigDecimal(sale),
                "/thumbnail.png",
                List.of(1L),
                created,
                new BigDecimal("4.5"),
                3);
    }
}
