package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.*;

import io.github.cskwork.lunepick.catalog.CatalogApi;
import io.github.cskwork.lunepick.catalog.CatalogStockApi;
import io.github.cskwork.lunepick.catalog.ProductChanged;
import io.github.cskwork.lunepick.catalog.internal.application.CatalogService;
import io.github.cskwork.lunepick.catalog.internal.application.CatalogStore;
import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.PageQuery;
import io.github.cskwork.lunepick.wishlist.internal.application.WishlistService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
class CatalogPersistenceIntegrationTest extends EmbeddedDatabase {
    static final long FIRST = 990101, SECOND = 990102, HIDDEN = 990103, MEMBER = 990199;
    @Autowired CatalogApi api;
    @Autowired CatalogStockApi stock;
    @Autowired CatalogService catalog;
    @Autowired CatalogStore store;
    @Autowired WishlistService wishlist;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents events;

    @BeforeEach
    void products() {
        jdbc.update(
                "INSERT INTO brand(id,name,slug,logo_url) VALUES(990100,'레코드"
                        + " 브랜드','record-brand','brand.png')");
        jdbc.update(
                "INSERT INTO category(id,parent_id,name,slug,sort_order) VALUES(990100,NULL,'레코드"
                        + " 카테고리','record-category',99)");
        for (long id : List.of(FIRST, SECOND, HIDDEN)) {
            jdbc.update(
                    """
                    INSERT INTO product(id,brand_id,name,description,base_price,sale_price,created_order,status)
                    VALUES(?,990100,?,'설명',10000,?,?,?)
                    """,
                    id,
                    "레코드 상품 " + id,
                    id == SECOND ? 7000 : 8000,
                    id,
                    id == HIDDEN ? "HIDDEN" : "ON_SALE");
            jdbc.update(
                    "INSERT INTO product_category(product_id,category_id) VALUES(?,990100)", id);
            jdbc.update(
                    "INSERT INTO"
                            + " product_option(id,product_id,name,price,status,available_quantity,sort_order)"
                            + " VALUES(?,?,'옵션',500,'ON_SALE',2,1)",
                    id,
                    id);
            jdbc.update(
                    "INSERT INTO product_image(id,product_id,url,sort_order,is_thumbnail)"
                            + " VALUES(?,?,'image.png',1,true)",
                    id,
                    id);
        }
        jdbc.update(
                """
                INSERT INTO product_review(id,product_id,member_name,rating,title,content,image_urls,created_at)
                VALUES(990101,990101,'회원',4.5,'제목','후기','["review.png"]'::jsonb,'2026-01-02T03:04:05Z')
                """);
    }

    @Test
    void recordMappersPreservePricesCategoriesOptionsImagesAndReviewJson() {
        var detail = catalog.detail(FIRST);
        assertThat(detail.brandName()).isEqualTo("레코드 브랜드");
        assertThat(detail.brandLogoUrl()).isEqualTo("brand.png");
        assertThat(detail.salePrice()).isEqualByComparingTo("8000");
        assertThat(detail.discountRate()).isEqualByComparingTo("20.0");
        assertThat(detail.rating()).isEqualByComparingTo("4.5");
        assertThat(detail.reviewCount()).isEqualTo(1);
        assertThat(detail.options()).hasSize(1);
        assertThat(detail.options().getFirst().optionPrice()).isEqualByComparingTo("500");
        assertThat(detail.images().getFirst().isThumbnail()).isTrue();
        assertThat(detail.categories()).extracting(c -> c.categoryId()).containsExactly(990100L);
        var reviews = catalog.reviews(FIRST, new PageQuery(0, 10));
        assertThat(reviews.meta().total()).isEqualTo(1);
        assertThat(reviews.items().getFirst().body()).isEqualTo("후기");
        assertThat(reviews.items().getFirst().imageUrls()).containsExactly("review.png");
        assertThat(reviews.items().getFirst().createdAt()).hasToString("2026-01-02T03:04:05Z");
        assertThat(catalog.reviews(SECOND, new PageQuery(0, 10)).items()).isEmpty();
        jdbc.update("UPDATE product_review SET image_urls=NULL WHERE id=990101");
        assertThat(catalog.reviews(FIRST, new PageQuery(0, 10)).items().getFirst().imageUrls())
                .isNull();
    }

    @Test
    void visibleReadModelsFilterHiddenBeforePagingAndKeepStableSorts() {
        assertThat(api.searchSnapshot()).noneMatch(p -> p.productId() == HIDDEN);
        assertThat(api.searchProduct(HIDDEN)).isEmpty();
        assertThat(api.searchProduct(FIRST)).isPresent();
        assertThat(api.summaries(List.of(FIRST, SECOND, HIDDEN, -1L)))
                .extracting(CatalogApi.ProductListItem::productId)
                .containsExactly(FIRST, SECOND);
        assertThat(api.summaries(List.of())).isEmpty();
        assertThat(api.searchSnapshot(FIRST - 1, 1))
                .extracting(CatalogApi.SearchProduct::productId)
                .containsExactly(FIRST);
        assertThat(api.searchSnapshot(FIRST, 1))
                .extracting(CatalogApi.SearchProduct::productId)
                .containsExactly(SECOND);
        assertThat(api.searchSnapshot(SECOND, 1)).isEmpty();
        assertThatThrownBy(() -> catalog.detail(HIDDEN)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> catalog.searchSnapshot(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        for (String sort : List.of("PRICE_ASC", "POPULAR", "LATEST")) {
            var result = catalog.products("990100", "990100", sort, new PageQuery(0, 1));
            assertThat(result.items())
                    .extracting(CatalogApi.ProductListItem::productId)
                    .containsExactly(SECOND);
            assertThat(result.meta().total()).isEqualTo(2);
        }
        assertThat(catalog.products("990100", null, "PRICE_DESC", new PageQuery(0, 2)).items())
                .extracting(CatalogApi.ProductListItem::productId)
                .containsExactly(FIRST, SECOND);
        assertThat(catalog.products(null, "990100", "anything", new PageQuery(0, 2)).items())
                .extracting(CatalogApi.ProductListItem::productId)
                .containsExactly(FIRST, SECOND);
        assertThat(catalog.products("990100", null, null, new PageQuery(0, 2)).items())
                .extracting(CatalogApi.ProductListItem::productId)
                .containsExactly(SECOND, FIRST);
        assertThat(catalog.products("990100", null, "LATEST", new PageQuery(2, 2)).items())
                .isEmpty();
        assertThat(store.products(List.of())).isEmpty();
    }

    @Test
    void wishlistCountsAndPaginatesVisibleProductsEvenWhenHiddenAndDeletedEntriesComeFirst() {
        for (long product : List.of(SECOND, FIRST, HIDDEN, -1L)) {
            jdbc.update(
                    "INSERT INTO wishlist_items(member_id,product_id) VALUES(?,?)",
                    MEMBER,
                    product);
        }
        var first = wishlist.list(MEMBER, new PageQuery(0, 1));
        assertThat(first.items()).extracting(i -> i.productId()).containsExactly(FIRST);
        assertThat(first.meta().total()).isEqualTo(2);
        var second = wishlist.list(MEMBER, new PageQuery(1, 1));
        assertThat(second.items()).extracting(i -> i.productId()).containsExactly(SECOND);
        assertThat(second.meta().total()).isEqualTo(2);
        var beyond = wishlist.list(MEMBER, new PageQuery(2, 1));
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.meta().total()).isEqualTo(2);
        assertThatThrownBy(() -> wishlist.add(MEMBER, HIDDEN)).isInstanceOf(ApiException.class);
    }

    @Test
    void stockBulkBoundaryAndReservationsChangeVisibleAvailability() {
        assertThat(stock.options(List.of())).isEmpty();
        var options = stock.options(List.of(FIRST, SECOND, -1L));
        assertThat(options).hasSize(2);
        assertThat(options)
                .allSatisfy(o -> assertThat(o.salePrice()).isGreaterThan(BigDecimal.ZERO));
        assertThat(stock.option(-1L)).isEmpty();
        stock.reserve(
                List.of(
                        new CatalogStockApi.Reservation(FIRST, 1),
                        new CatalogStockApi.Reservation(FIRST, 1)));
        assertThat(api.searchProduct(FIRST).orElseThrow().inStock()).isFalse();
        assertThat(api.searchProduct(FIRST).orElseThrow().toListItem().inStock()).isFalse();
        stock.release(List.of(new CatalogStockApi.Reservation(FIRST, 2)));
        assertThat(api.searchProduct(FIRST).orElseThrow().inStock()).isTrue();
        jdbc.update("UPDATE product_option SET available_quantity=NULL WHERE id=?", FIRST);
        stock.reserve(List.of(new CatalogStockApi.Reservation(FIRST, 99)));
        stock.release(List.of(new CatalogStockApi.Reservation(FIRST, 99)));
        assertThat(stock.option(FIRST).orElseThrow().availableQuantity()).isNull();
        jdbc.update("UPDATE product SET status='STOPPED' WHERE id=?", FIRST);
        assertThat(stock.option(FIRST).orElseThrow().status()).isEqualTo("STOPPED");
        assertThat(api.searchProduct(FIRST).orElseThrow().inStock()).isFalse();
    }

    @Test
    void inventoryPublishesOnlyAvailabilityFlipsAndNeverWritesUnlimitedOptions() {
        stock.reserve(List.of(new CatalogStockApi.Reservation(FIRST, 1)));
        assertThat(events.stream(ProductChanged.class).count()).isZero();
        stock.reserve(List.of(new CatalogStockApi.Reservation(FIRST, 1)));
        assertThat(events.stream(ProductChanged.class).map(ProductChanged::productId))
                .containsExactly(FIRST);
        stock.release(List.of(new CatalogStockApi.Reservation(FIRST, 1)));
        assertThat(events.stream(ProductChanged.class).count()).isEqualTo(2);
        stock.release(List.of(new CatalogStockApi.Reservation(FIRST, 1)));
        assertThat(events.stream(ProductChanged.class).count()).isEqualTo(2);
        jdbc.update("UPDATE product_option SET available_quantity=NULL WHERE id=?", FIRST);
        String version =
                jdbc.queryForObject(
                        "SELECT ctid::text FROM product_option WHERE id=?", String.class, FIRST);
        stock.reserve(List.of(new CatalogStockApi.Reservation(FIRST, 99)));
        stock.release(List.of(new CatalogStockApi.Reservation(FIRST, 99)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT ctid::text FROM product_option WHERE id=?",
                                String.class,
                                FIRST))
                .isEqualTo(version);
        assertThat(events.stream(ProductChanged.class).count()).isEqualTo(2);
    }

    @Test
    void stockFailurePathsRejectInvalidMissingAndUnavailableOptions() {
        assertThatThrownBy(() -> stock.reserve(List.of(new CatalogStockApi.Reservation(FIRST, 0))))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        assertThatThrownBy(() -> stock.reserve(List.of(new CatalogStockApi.Reservation(FIRST, 3))))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_STOCK"));
        assertThat(stock.option(FIRST).orElseThrow().availableQuantity()).isEqualTo(2);
        jdbc.update("UPDATE product_option SET status='SOLD_OUT' WHERE id=?", FIRST);
        assertThat(api.searchProduct(FIRST).orElseThrow().inStock()).isFalse();
        assertThatThrownBy(() -> stock.reserve(List.of(new CatalogStockApi.Reservation(FIRST, 1))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> stock.release(List.of(new CatalogStockApi.Reservation(-1, 1))))
                .isInstanceOf(ApiException.class);
    }
}
