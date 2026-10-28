package io.github.cskwork.lunepick.search.internal.application;

import io.github.cskwork.lunepick.catalog.CatalogApi.SearchProduct;
import io.github.cskwork.lunepick.search.SearchApi.SearchResult;
import io.github.cskwork.lunepick.search.internal.domain.SearchQuery;
import java.util.List;

public interface SearchStore {
    SearchResult search(SearchQuery query, int size, long offset);

    List<String> autocomplete(String prefix, int size);

    void lockForInitialization();

    boolean isEmpty();

    void remove(long productId);

    void upsert(SearchProduct product);
}
