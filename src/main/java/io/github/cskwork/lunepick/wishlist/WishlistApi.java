package io.github.cskwork.lunepick.wishlist;

import io.github.cskwork.lunepick.shared.PageMeta;
import io.github.cskwork.lunepick.shared.PageQuery;
import java.math.BigDecimal;
import java.util.List;

/** Member-scoped wishlist commands and immutable page snapshots. */
public interface WishlistApi {
    Page list(long memberId, PageQuery page);

    void add(long memberId, long productId);

    void remove(long memberId, long productId);

    record Item(
            long wishlistId,
            long productId,
            String productName,
            String brandName,
            String thumbnailUrl,
            BigDecimal salePrice,
            BigDecimal originalPrice,
            BigDecimal discountRate) {}

    record Page(List<Item> items, PageMeta meta) {}
}
