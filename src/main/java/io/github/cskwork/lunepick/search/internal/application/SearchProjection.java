package io.github.cskwork.lunepick.search.internal.application;

import io.github.cskwork.lunepick.catalog.CatalogApi;
import io.github.cskwork.lunepick.catalog.CatalogApi.SearchProduct;
import java.util.Optional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SearchProjection {
    private final CatalogApi catalog;
    private final SearchStore repository;

    public SearchProjection(CatalogApi catalog, SearchStore repository) {
        this.catalog = catalog;
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void rebuild() {
        repository.lockForInitialization();
        if (!repository.isEmpty()) return;
        long after = 0;
        while (true) {
            var page = catalog.searchSnapshot(after, 250);
            page.forEach(repository::upsert);
            if (page.size() < 250) return;
            after = page.getLast().productId();
        }
    }

    public Optional<SearchProduct> product(long productId) {
        return catalog.searchProduct(productId);
    }

    public void update(long productId, Optional<SearchProduct> product) {
        product.ifPresentOrElse(repository::upsert, () -> repository.remove(productId));
    }
}
