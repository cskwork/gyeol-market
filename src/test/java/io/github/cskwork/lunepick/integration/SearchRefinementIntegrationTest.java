package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.cskwork.lunepick.catalog.CatalogStockApi;
import io.github.cskwork.lunepick.catalog.ProductChanged;
import io.github.cskwork.lunepick.search.SearchApi;
import io.github.cskwork.lunepick.search.internal.application.SearchIndexJobs;
import io.github.cskwork.lunepick.search.internal.application.SearchIndexWorker;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchRefinementIntegrationTest extends EmbeddedDatabase {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired SearchApi search;
    @Autowired SearchIndexJobs jobs;
    @Autowired SearchIndexWorker worker;
    @Autowired CatalogStockApi stock;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate transactions;

    @AfterEach
    void removeSearchFixtures() {
        jdbc.update("DELETE FROM search_documents WHERE product_id BETWEEN 880001 AND 880004");
    }

    @Test
    void invalidAutocompleteAndSearchIdentifiersReturnStructuredClientErrors() throws Exception {
        for (String path :
                List.of(
                        "/api/search/autocomplete?prefix=cream&size=2.5",
                        "/api/search/autocomplete?prefix=cream&size=-1",
                        "/api/search/autocomplete?prefix=cream&size=101",
                        "/api/search/products?categoryId=invalid",
                        "/api/search/products?categoryId=0",
                        "/api/search/products?brandId=Infinity")) {
            mvc.perform(get(path))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
        }
    }

    @Test
    void fullTextFindsIndependentTermsAcrossFieldsAndRanksRelevantResults() {
        document(880001, "cedar", "gentle botanical daily skin cleanser");
        document(880002, "cedar cleanser", "");
        var matches = search.search("cleanser cedar", null, "RELEVANCE", 20, 0);
        assertThat(matches.products())
                .extracting(p -> p.productId())
                .containsExactly(880002L, 880001L);
        assertThat(matches.total()).isEqualTo(2);
        // Reversed terms have no matching literal substring in any field: this requires FTS.
        assertThat(search.search("cedar missingterm", null, "RELEVANCE", 20, 0).products())
                .isEmpty();
        assertThat(search.search("cleanser cedar", null, "LATEST", 20, 0).products())
                .extracting(p -> p.productId())
                .containsExactly(880001L, 880002L);
    }

    @Test
    void koreanInfixNameMatchesRankAboveDescriptionAndBrandOnlyMatches() {
        document(880001, "로션", "크림");
        document(880002, "선크림", "");
        document(880003, "크림", "");
        document(880004, "토너", "");
        jdbc.update("UPDATE search_documents SET brand_name='크림' WHERE product_id=880004");

        var matches = search.search("크림", null, "RELEVANCE", 100, 0);
        assertThat(
                        matches.products().stream()
                                .filter(product -> product.productId() >= 880001)
                                .map(product -> product.productId()))
                .containsExactly(880003L, 880002L, 880001L, 880004L);
        // Description/brand FTS matches remain searchable, while name similarity promotes the
        // infix.
        assertThat(
                        search.search("크림", null, "LATEST", 100, 0).products().stream()
                                .filter(product -> product.productId() >= 880001)
                                .map(product -> product.productId()))
                .containsExactly(880001L, 880002L, 880003L, 880004L);
    }

    @Test
    void autocompleteKeepsLiteralInfixAndRanksPrefixesBeforeOtherMatches() {
        document(880001, "Hydrating serum", "");
        document(880002, "moisturizer", "");
        document(880003, "Daily hydrating serum", "");
        assertThat(search.autocomplete("hydra", "8").suggestions())
                .containsExactly("Hydrating serum", "Daily hydrating serum");
        assertThat(search.autocomplete("HYDRA", "8").suggestions())
                .containsExactly("Hydrating serum", "Daily hydrating serum");
        assertThat(search.autocomplete("선", "10").suggestions())
                .containsExactly("키즈 매일 선크림 SPF50+ PA++++");
        assertThat(search.autocomplete("spf", "10").suggestions())
                .containsExactly("키즈 매일 선크림 SPF50+ PA++++");
        assertThat(search.autocomplete("moisturizr", "8").suggestions()).contains("moisturizer");
        assertThat(search.autocomplete("%_!", "8").suggestions()).isEmpty();
    }

    @Test
    void committedStockEventChangesSearchAvailabilityAndHiddenProductIsRemoved() throws Exception {
        Long originalQuantity =
                jdbc.queryForObject(
                        "SELECT available_quantity FROM product_option WHERE id=14", Long.class);
        try {
            jdbc.update("UPDATE product_option SET available_quantity=1 WHERE id=14");
            jobs.enqueue(13);
            while (worker.processBatch() > 0) {
                /* drain existing publications */
            }
            assertThat(
                            search.search("플루이드", null, "RELEVANCE", 20, 0)
                                    .products()
                                    .getFirst()
                                    .inStock())
                    .isTrue();
            transactions.executeWithoutResult(
                    status -> stock.reserve(List.of(new CatalogStockApi.Reservation(14, 1))));
            awaitPending(13);
            while (worker.processBatch() > 0) {
                /* apply stock event through the real worker */
            }
            assertThat(
                            search.search("플루이드", null, "RELEVANCE", 20, 0)
                                    .products()
                                    .getFirst()
                                    .inStock())
                    .isFalse();
            transactions.executeWithoutResult(
                    status -> {
                        jdbc.update("UPDATE product SET status='HIDDEN' WHERE id=13");
                        events.publishEvent(new ProductChanged(13));
                    });
            awaitPending(13);
            while (worker.processBatch() > 0) {
                /* apply hidden-product event */
            }
            assertThat(search.search("플루이드", null, "RELEVANCE", 20, 0).products()).isEmpty();
        } finally {
            jdbc.update(
                    "UPDATE product_option SET available_quantity=? WHERE id=14", originalQuantity);
            jdbc.update("UPDATE product SET status='ON_SALE' WHERE id=13");
            jobs.enqueue(13);
            while (worker.processBatch() > 0) {
                /* restore through the public catalog boundary */
            }
        }
    }

    private void document(long id, String name, String description) {
        jdbc.update(
                """
        INSERT INTO search_documents(product_id,brand_id,brand_name,product_name,description,
            sale_price,original_price,discount_rate,category_ids,created_order,rating,review_count)
        VALUES (?,1,'test fixture',?,?,1,1,0,'{}',1,0,0)
        """,
                id,
                name,
                description);
    }

    private void awaitPending(long productId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (Boolean.TRUE.equals(
                    jdbc.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM search_index_jobs WHERE product_id=? AND status IN"
                                    + " ('QUEUED','RETRY'))",
                            Boolean.class,
                            productId))) return;
            Thread.sleep(20);
        }
        throw new AssertionError(
                "Committed catalog event did not enqueue a search refresh within five seconds");
    }
}
