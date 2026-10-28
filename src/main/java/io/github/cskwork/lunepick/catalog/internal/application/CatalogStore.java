package io.github.cskwork.lunepick.catalog.internal.application;

import static io.github.cskwork.lunepick.catalog.internal.application.CatalogViews.*;

import io.github.cskwork.lunepick.catalog.internal.domain.CatalogProduct;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CatalogStore {
    List<CatalogProduct> products();

    List<CatalogProduct> products(long afterProductId, int size);

    List<CatalogProduct> products(Collection<Long> ids);

    ProductPage productPage(Long categoryId, Long brandId, String sort, long offset, int size);

    Optional<CatalogProduct> product(long id);

    List<Category> categories();

    List<Option> options(long productId);

    List<Image> images(long productId);

    List<Review> reviews(long productId, int offset, int size);

    long reviewCount(long productId);

    record ProductPage(List<CatalogProduct> products, long total) {}
}
