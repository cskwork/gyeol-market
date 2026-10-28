package io.github.cskwork.lunepick.wishlist.internal.application;

import io.github.cskwork.lunepick.catalog.CatalogApi;
import io.github.cskwork.lunepick.shared.*;
import io.github.cskwork.lunepick.wishlist.WishlistApi;
import io.github.cskwork.lunepick.wishlist.internal.config.WishlistProperties;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WishlistService implements WishlistApi {
    private final WishlistStore store;
    private final CatalogApi catalog;
    private final WishlistProperties properties;

    public WishlistService(WishlistStore store, CatalogApi catalog, WishlistProperties properties) {
        this.store = store;
        this.catalog = catalog;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Page list(long memberId, PageQuery page) {
        var entries = store.entries(memberId, properties.maxEntries());
        var products =
                catalog
                        .summaries(entries.stream().map(WishlistStore.Entry::productId).toList())
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        CatalogApi.ProductListItem::productId,
                                        Function.identity()));
        var visible =
                entries.stream().filter(entry -> products.containsKey(entry.productId())).toList();
        var items =
                visible.stream()
                        .skip(page.offset())
                        .limit(page.limit())
                        .map(
                                entry -> {
                                    var product = products.get(entry.productId());
                                    return new Item(
                                            entry.id(),
                                            product.productId(),
                                            product.productName(),
                                            product.brandName(),
                                            product.thumbnailUrl(),
                                            product.salePrice(),
                                            product.originalPrice(),
                                            product.discountRate());
                                })
                        .toList();
        return new Page(items, new PageMeta(page.page(), page.size(), visible.size()));
    }

    public void add(long memberId, long productId) {
        if (catalog.searchProduct(productId).isEmpty())
            throw new ApiException(404, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다.");
        if (!store.add(memberId, productId, properties.maxEntries())) {
            throw new ApiException(
                    409,
                    "WISHLIST_LIMIT_REACHED",
                    "위시리스트는 최대 " + properties.maxEntries() + "개까지 담을 수 있습니다.");
        }
    }

    public void remove(long memberId, long productId) {
        store.remove(memberId, productId);
    }
}
