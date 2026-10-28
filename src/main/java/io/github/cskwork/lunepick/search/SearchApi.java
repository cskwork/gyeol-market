package io.github.cskwork.lunepick.search;

import io.github.cskwork.lunepick.catalog.CatalogApi.ProductListItem;
import java.util.List;

/** Query-only search boundary; results never expose projection persistence objects. */
public interface SearchApi {
    SearchResult search(String keyword, String categoryId, String sort, int size, long offset);

    AutocompleteResponse autocomplete(String prefix, String size);

    PopularResponse popular();

    record SearchResult(List<ProductListItem> products, long total) {}

    record AutocompleteResponse(List<String> suggestions) {}

    record PopularKeyword(String keyword, int rank) {}

    record PopularResponse(List<PopularKeyword> keywords) {}
}
