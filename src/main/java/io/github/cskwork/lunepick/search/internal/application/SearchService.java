package io.github.cskwork.lunepick.search.internal.application;

import io.github.cskwork.lunepick.search.SearchApi;
import io.github.cskwork.lunepick.search.internal.domain.SearchQuery;
import io.github.cskwork.lunepick.shared.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SearchService implements SearchApi {
    private final SearchStore repository;

    public SearchService(SearchStore repository) {
        this.repository = repository;
    }

    public SearchResult search(
            String keyword, String categoryId, String sort, int size, long offset) {
        return repository.search(SearchQuery.from(keyword, categoryId, sort), size, offset);
    }

    public AutocompleteResponse autocomplete(String prefix, String size) {
        int limit = autocompleteLimit(size);
        String normalized = prefix == null ? "" : prefix.trim();
        return new AutocompleteResponse(
                normalized.isEmpty() ? List.of() : repository.autocomplete(normalized, limit));
    }

    public PopularResponse popular() {
        // Seed-backed suggestions, not a claim of measured search traffic. The mock ignores size.
        return new PopularResponse(
                List.of(
                        new PopularKeyword("선크림", 1),
                        new PopularKeyword("토너", 2),
                        new PopularKeyword("마스크팩", 3),
                        new PopularKeyword("쿠션", 4),
                        new PopularKeyword("샴푸", 5),
                        new PopularKeyword("립 틴트", 6)));
    }

    private int autocompleteLimit(String size) {
        if (size == null) return 10;
        try {
            int value = size.matches("[0-9]+") ? Integer.parseInt(size) : 0;
            if (value >= 1 && value <= 100) return value;
        } catch (NumberFormatException invalid) {
            // Handled by the same explicit client-error path as out-of-range values.
        }
        throw new ApiException(
                400, "INVALID_PARAMETER", "Autocomplete size must be an integer between 1 and 100");
    }
}
