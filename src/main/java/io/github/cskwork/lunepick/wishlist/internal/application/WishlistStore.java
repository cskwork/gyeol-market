package io.github.cskwork.lunepick.wishlist.internal.application;

import java.util.List;

public interface WishlistStore {
    record Entry(long id, long productId) {}

    List<Entry> entries(long memberId, int limit);

    boolean add(long memberId, long productId, int maxEntries);

    void remove(long memberId, long productId);
}
