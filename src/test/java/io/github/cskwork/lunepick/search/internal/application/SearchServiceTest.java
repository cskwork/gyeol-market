package io.github.cskwork.lunepick.search.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.cskwork.lunepick.search.SearchApi;
import io.github.cskwork.lunepick.search.internal.domain.SearchQuery;
import io.github.cskwork.lunepick.shared.ApiException;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class SearchServiceTest {
    private final SearchStore repository = mock(SearchStore.class);
    private final SearchService service = new SearchService(repository);

    @Test
    void searchPassesNormalizedQueryAndPaginationToStore() {
        var result = new SearchApi.SearchResult(List.of(), 25);
        var expected = SearchQuery.from("SPF", "2", "RELEVANCE");
        when(repository.search(expected, 20, 40)).thenReturn(result);
        assertThat(service.search("  SPF  ", "2", "RELEVANCE", 20, 40)).isEqualTo(result);
    }

    @Test
    void blankPrefixDoesNotQueryTheDatabase() {
        assertThat(service.autocomplete("  ", null).suggestions()).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void autocompletePreservesOrdinaryInfixInputsWhileTrimming() {
        when(repository.autocomplete("SPF", 10)).thenReturn(List.of("키즈 선크림 SPF50+"));
        assertThat(service.autocomplete("  SPF  ", null).suggestions())
                .containsExactly("키즈 선크림 SPF50+");
        verify(repository).autocomplete("SPF", 10);
        when(repository.autocomplete("선", 10)).thenReturn(List.of("키즈 매일 선크림 SPF50+ PA++++"));
        assertThat(service.autocomplete("  선  ", null).suggestions())
                .containsExactly("키즈 매일 선크림 SPF50+ PA++++");
    }

    @Test
    void autocompleteRejectsInvalidOrUnboundedLimits() {
        for (String value : List.of("0", "-1", "10000", "2.9", "invalid", "NaN", "Infinity", "")) {
            Assertions.assertThatThrownBy(() -> service.autocomplete("크림", value))
                    .isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void autocompleteAcceptsIntegerBounds() {
        service.autocomplete("크림", "1");
        service.autocomplete("크림", "100");
        verify(repository).autocomplete("크림", 1);
        verify(repository).autocomplete("크림", 100);
    }

    @Test
    void popularKeywordsAreTheSixRankedFixtureKeywords() {
        var keywords = service.popular().keywords();
        assertThat(keywords)
                .extracting(SearchService.PopularKeyword::keyword)
                .containsExactly("선크림", "토너", "마스크팩", "쿠션", "샴푸", "립 틴트");
        assertThat(keywords)
                .extracting(SearchService.PopularKeyword::rank)
                .containsExactly(1, 2, 3, 4, 5, 6);
        verifyNoInteractions(repository);
    }
}
