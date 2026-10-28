package io.github.cskwork.lunepick.search.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cskwork.lunepick.shared.ApiException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SearchQueryTest {
    @Test
    void normalizesKeywordAndKeepsCategoryFilter() {
        var query = SearchQuery.from("  SPF50+ 크림  ", "3", "RELEVANCE");
        assertThat(query.keyword()).isEqualTo("spf50+ 크림");
        assertThat(query.categoryId()).isEqualTo(3L);
        assertThat(SearchQuery.from(null, null, null).keyword()).isEmpty();
    }

    @Test
    void invalidCategoryIsAClientErrorAndMissingCategoryDoesNotFilter() {
        assertThat(SearchQuery.from("", null, null).categoryId()).isNull();
        for (String value :
                List.of("invalid", "1.5", "", "1.0", "0", "-1", "1e0", "99999999999999999999999")) {
            assertThatThrownBy(() -> SearchQuery.from("", value, null))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void includesTreatsSqlWildcardsAsLiteralCharacters() {
        assertThat(SearchQuery.containsPattern("50%_!")).isEqualTo("%50!%!_!!%");
    }

    @ParameterizedTest
    @MethodSource("sorts")
    void matchesMockSortAndUsesStableIdTies(String sort, String orderBy) {
        assertThat(SearchQuery.from("", null, sort).orderBy()).isEqualTo(orderBy);
    }

    static Stream<Arguments> sorts() {
        return Stream.of(
                Arguments.of(null, "created_order DESC, product_id ASC"),
                Arguments.of("LATEST", "created_order DESC, product_id ASC"),
                Arguments.of("POPULAR", "discount_rate DESC, product_id ASC"),
                Arguments.of("PRICE_ASC", "sale_price ASC, product_id ASC"),
                Arguments.of("PRICE_DESC", "sale_price DESC, product_id ASC"),
                Arguments.of("RELEVANCE", "relevance DESC, product_id ASC"),
                Arguments.of("RATING", "product_id ASC"),
                Arguments.of("unrecognized; DROP TABLE search_documents", "product_id ASC"));
    }
}
