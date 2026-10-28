package io.github.cskwork.lunepick.wishlist.internal.persistence;

import io.github.cskwork.lunepick.wishlist.internal.application.WishlistStore;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWishlistStore implements WishlistStore {
    private final JdbcClient jdbc;

    public JdbcWishlistStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Entry> entries(long memberId, int limit) {
        return jdbc.sql(
                        "SELECT id, product_id FROM wishlist_items WHERE member_id=:member ORDER BY"
                                + " id DESC LIMIT :limit")
                .param("member", memberId)
                .param("limit", limit)
                .query(Entry.class)
                .list();
    }

    public boolean add(long memberId, long productId, int maxEntries) {
        // A module-specific member lock serializes the cap check with concurrent additions.
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "lunepick.wishlist.member:" + memberId)
                .query(Integer.class)
                .single();
        boolean alreadyPresent =
                jdbc.sql(
                                "SELECT EXISTS(SELECT 1 FROM wishlist_items WHERE member_id=:member AND product_id=:product)")
                        .param("member", memberId)
                        .param("product", productId)
                        .query(Boolean.class)
                        .single();
        if (alreadyPresent) return true;
        long count =
                jdbc.sql(
                                "SELECT count(*) FROM (SELECT 1 FROM wishlist_items WHERE member_id=:member LIMIT :limit) entries")
                        .param("member", memberId)
                        .param("limit", maxEntries)
                        .query(Long.class)
                        .single();
        if (count >= maxEntries) return false;
        jdbc.sql(
                        "INSERT INTO wishlist_items(member_id,product_id) VALUES(:member,:product)"
                                + " ON CONFLICT DO NOTHING")
                .param("member", memberId)
                .param("product", productId)
                .update();
        return true;
    }

    public void remove(long memberId, long productId) {
        jdbc.sql("DELETE FROM wishlist_items WHERE member_id=:member AND product_id=:product")
                .param("member", memberId)
                .param("product", productId)
                .update();
    }
}
