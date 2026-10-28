package io.github.cskwork.lunepick.catalog.internal.application;

import static io.github.cskwork.lunepick.catalog.internal.application.CatalogViews.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.cskwork.lunepick.catalog.internal.domain.CatalogProduct;
import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.PageQuery;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogServiceTest {
    private CatalogStore store;
    private CatalogService service;
    private final CatalogProduct first = product(1, 1, List.of(1L, 2L), 20000);
    private final CatalogProduct second = product(2, 2, List.of(1L), 22400);
    private final CatalogProduct third = product(3, 2, List.of(2L), 9900);
    private final PageQuery page = new PageQuery(0, 20);

    @BeforeEach
    void setUp() {
        store = mock(CatalogStore.class);
        service = new CatalogService(store);
        when(store.products()).thenReturn(List.of(first, second, third));
        when(store.productPage(any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(new CatalogStore.ProductPage(List.of(), 0));
        when(store.productPage(null, null, null, 0, 20))
                .thenReturn(new CatalogStore.ProductPage(List.of(third, second, first), 3));
        when(store.productPage(1L, 2L, null, 0, 20))
                .thenReturn(new CatalogStore.ProductPage(List.of(second), 1));
        when(store.productPage(1L, null, "LATEST", 0, 20))
                .thenReturn(new CatalogStore.ProductPage(List.of(second, first), 2));
        when(store.categories())
                .thenReturn(
                        List.of(
                                new Category(1, null, "스킨케어", "skincare", 1),
                                new Category(2, null, "메이크업", "makeup", 2)));
        when(store.product(1)).thenReturn(Optional.of(first));
    }

    @Test
    void exposesImmutableSearchSnapshotsAndMissingSingleProduct() {
        assertThat(service.searchSnapshot())
                .extracting(p -> p.productId())
                .containsExactly(1L, 2L, 3L);
        assertThat(service.searchProduct(1)).hasValue(first.searchProduct());
        assertThat(service.searchProduct(99)).isEmpty();
    }

    @Test
    void buildsOrderedRootAndChildCategoryTree() {
        when(store.categories())
                .thenReturn(
                        List.of(
                                new Category(2, null, "메이크업", "makeup", 2),
                                new Category(4, 1L, "크림", "cream", 2),
                                new Category(1, null, "스킨케어", "skincare", 1),
                                new Category(3, 1L, "토너", "toner", 1)));
        var tree = service.categories().categories();
        assertThat(tree).extracting(CategoryNode::id).containsExactly(1L, 2L);
        assertThat(tree.getFirst().children()).extracting(CategoryNode::id).containsExactly(3L, 4L);
        assertThat(tree.getLast().children()).isEmpty();
    }

    @Test
    void filtersCategoryAndBrandTogetherAndRejectsGarbage() {
        assertThat(service.products("1", "2", null, page).items())
                .extracting(p -> p.productId())
                .containsExactly(2L);
        assertThat(service.products("99", null, null, page).items()).isEmpty();
        for (String invalid :
                List.of("invalid", "1.5", "1.0", "", "0", "Infinity", "9223372036854775808")) {
            assertThatThrownBy(() -> service.products(invalid, null, null, page))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> service.products(null, invalid, null, page))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void paginatesAfterFilteringAndSortingWithUnchangedTotal() {
        when(store.productPage(null, null, "PRICE_ASC", 1, 1))
                .thenReturn(new CatalogStore.ProductPage(List.of(first), 3));
        var result = service.products(null, null, "PRICE_ASC", new PageQuery(1, 1));
        assertThat(result.items()).extracting(p -> p.productId()).containsExactly(1L);
        assertThat(result.meta().page()).isEqualTo(1);
        assertThat(result.meta().size()).isEqualTo(1);
        assertThat(result.meta().total()).isEqualTo(3);
        assertThat(
                        service.products(null, null, null, new PageQuery(Integer.MAX_VALUE, 100))
                                .items())
                .isEmpty();
        verify(store).productPage(null, null, "PRICE_ASC", 1, 1);
        verify(store).productPage(null, null, null, 214748364700L, 100);
    }

    @Test
    void validatesCategoryPathButNotOptionalProductFilter() {
        assertThat(service.categoryProducts("1", "LATEST", page).items())
                .extracting(p -> p.productId())
                .containsExactly(2L, 1L);
        assertThatThrownBy(() -> service.categoryProducts("invalid", null, page))
                .isInstanceOf(ApiException.class)
                .hasMessage("잘못된 카테고리입니다.");
        assertThatThrownBy(() -> service.categoryProducts("99", null, page))
                .isInstanceOf(ApiException.class)
                .hasMessage("카테고리를 찾을 수 없습니다.");
    }

    @Test
    void detailHasOptionsImagesCategoriesAndSeedValues() {
        var options = List.of(new Option(1, "50ml", BigDecimal.ZERO, "ON_SALE", null));
        var images = List.of(new Image(10, "/thumbnail.png", 1, true));
        when(store.options(1)).thenReturn(options);
        when(store.images(1)).thenReturn(images);
        var detail = service.detail(1);
        assertThat(detail.productId()).isEqualTo(1);
        assertThat(detail.options()).isEqualTo(options);
        assertThat(detail.images()).isEqualTo(images);
        assertThat(detail.categories())
                .extracting(CategoryPath::categoryId)
                .containsExactly(1L, 2L);
        assertThat(detail.rating()).isEqualByComparingTo("0");
        assertThat(detail.reviewCount()).isZero();
        assertThat(detail.brandLogoUrl()).isNull();
        assertThat(detail.thumbnailUrl()).isEqualTo("/thumbnail.png");
    }

    @Test
    void detailAndReviewsRejectMissingProductBeforeOtherReads() {
        assertThatThrownBy(() -> service.detail(99))
                .isInstanceOf(ApiException.class)
                .hasMessage("상품을 찾을 수 없습니다.");
        assertThatThrownBy(() -> service.reviews(99, page)).isInstanceOf(ApiException.class);
        verify(store, never()).options(99);
        verify(store, never()).reviewCount(99);
    }

    @Test
    void returnsEmptySeedReviewsWithReviewSpecificPagination() {
        when(store.reviews(1, 10, 10)).thenReturn(List.of());
        var result = service.reviews(1, new PageQuery(1, 10));
        assertThat(result.items()).isEmpty();
        assertThat(result.meta().total()).isZero();
        assertThat(result.meta().page()).isEqualTo(1);
        assertThat(result.meta().size()).isEqualTo(10);
    }

    @Test
    void reviewsPreserveTotalForAnOffsetBeyondJpaIntegerRange() {
        when(store.reviewCount(1)).thenReturn(7L);
        var result = service.reviews(1, new PageQuery(Integer.MAX_VALUE, 100));
        assertThat(result.items()).isEmpty();
        assertThat(result.meta().total()).isEqualTo(7);
        verify(store, never()).reviews(anyLong(), anyInt(), anyInt());
    }

    private CatalogProduct product(long id, long brand, List<Long> categories, int sale) {
        return new CatalogProduct(
                id,
                brand,
                "brand" + brand,
                null,
                "product" + id,
                "description",
                new BigDecimal("25000"),
                BigDecimal.valueOf(sale),
                "/thumbnail.png",
                categories,
                id,
                BigDecimal.ZERO,
                0);
    }
}
