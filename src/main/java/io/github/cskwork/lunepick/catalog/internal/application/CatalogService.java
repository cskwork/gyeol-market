package io.github.cskwork.lunepick.catalog.internal.application;

import static io.github.cskwork.lunepick.catalog.internal.application.CatalogViews.*;

import io.github.cskwork.lunepick.catalog.CatalogApi;
import io.github.cskwork.lunepick.catalog.internal.domain.CatalogProduct;
import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.PageMeta;
import io.github.cskwork.lunepick.shared.PageQuery;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogService implements CatalogApi {
    private final CatalogStore store;

    public CatalogService(CatalogStore store) {
        this.store = store;
    }

    @Override
    public List<SearchProduct> searchSnapshot() {
        return store.products().stream().map(CatalogProduct::searchProduct).toList();
    }

    @Override
    public Optional<SearchProduct> searchProduct(long productId) {
        return store.product(productId).map(CatalogProduct::searchProduct);
    }

    @Override
    public List<SearchProduct> searchSnapshot(long afterProductId, int size) {
        if (size < 1 || size > 1000) throw new IllegalArgumentException("size must be 1..1000");
        return store.products(afterProductId, size).stream()
                .map(CatalogProduct::searchProduct)
                .toList();
    }

    @Override
    public List<ProductListItem> summaries(Collection<Long> productIds) {
        if (productIds.isEmpty()) return List.of();
        return store.products(productIds).stream().map(CatalogProduct::listItem).toList();
    }

    public CategoryTree categories() {
        return new CategoryTree(children(store.categories(), null));
    }

    private List<CategoryNode> children(List<Category> all, Long parentId) {
        return all.stream()
                .filter(c -> Objects.equals(c.parentId(), parentId))
                .sorted(
                        Comparator.comparingInt(Category::sortOrder)
                                .thenComparingLong(Category::id))
                .map(c -> new CategoryNode(c.id(), c.name(), c.slug(), children(all, c.id())))
                .toList();
    }

    public Page<ProductListItem> categoryProducts(String categoryId, String sort, PageQuery page) {
        long id;
        try {
            if (categoryId == null || !categoryId.matches("[1-9][0-9]*"))
                throw new NumberFormatException();
            id = Long.parseLong(categoryId);
            if (id < 1) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new ApiException(400, "INVALID_PARAMETER", "잘못된 카테고리입니다.");
        }
        if (store.categories().stream().noneMatch(c -> c.id() == id)) {
            throw new ApiException(404, "CATEGORY_NOT_FOUND", "카테고리를 찾을 수 없습니다.");
        }
        return products(Long.toString(id), null, sort, page);
    }

    public Page<ProductListItem> products(
            String categoryId, String brandId, String sort, PageQuery page) {
        Long category = filterId(categoryId);
        Long brand = filterId(brandId);
        CatalogStore.ProductPage result =
                store.productPage(category, brand, sort, page.offset(), page.limit());
        return new Page<>(
                result.products().stream().map(CatalogProduct::listItem).toList(),
                new PageMeta(page.page(), page.size(), result.total()));
    }

    private Long filterId(String value) {
        if (value == null) return null;
        try {
            if (!value.matches("[1-9][0-9]*")) throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id < 1) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) {
            throw new ApiException(400, "INVALID_PARAMETER", "필터 ID는 양의 정수여야 합니다.");
        }
    }

    public ProductDetail detail(long id) {
        CatalogProduct product = requireProduct(id);
        List<CategoryPath> paths =
                store.categories().stream()
                        .filter(c -> product.categoryIds().contains(c.id()))
                        .sorted(
                                Comparator.comparingInt(Category::sortOrder)
                                        .thenComparingLong(Category::id))
                        .map(c -> new CategoryPath(c.id(), c.name(), c.slug()))
                        .toList();
        return ProductDetail.from(product, store.options(id), store.images(id), paths);
    }

    public Page<Review> reviews(long productId, PageQuery page) {
        requireProduct(productId);
        long total = store.reviewCount(productId);
        List<Review> reviews =
                page.offset() > Integer.MAX_VALUE
                        ? List.of()
                        : store.reviews(productId, (int) page.offset(), page.limit());
        return new Page<>(reviews, new PageMeta(page.page(), page.size(), total));
    }

    private CatalogProduct requireProduct(long id) {
        return store.product(id)
                .orElseThrow(() -> new ApiException(404, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."));
    }
}
