package io.github.cskwork.lunepick.cart.internal.persistence;

import io.github.cskwork.lunepick.cart.internal.application.CartStore;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCartStore implements CartStore {
    private final JdbcClient jdbc;

    public JdbcCartStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Line> lines(long memberId) {
        return jdbc.sql(
                        "SELECT id, option_id, quantity FROM cart_items WHERE member_id = :member"
                                + " ORDER BY id")
                .param("member", memberId)
                .query(Line.class)
                .list();
    }

    public Line add(long memberId, long optionId, int quantity) {
        return jdbc.sql(
                        """
                        INSERT INTO cart_items(member_id,option_id,quantity) VALUES(:member,:option,:quantity)
                        ON CONFLICT(member_id,option_id) DO UPDATE
                           SET quantity=least(99,cart_items.quantity+excluded.quantity)
                        RETURNING id,option_id,quantity
                        """)
                .param("member", memberId)
                .param("option", optionId)
                .param("quantity", quantity)
                .query(Line.class)
                .single();
    }

    public boolean update(long memberId, long itemId, int quantity) {
        return jdbc.sql(
                                "UPDATE cart_items SET quantity=:quantity WHERE member_id=:member"
                                        + " AND id=:id")
                        .param("quantity", quantity)
                        .param("member", memberId)
                        .param("id", itemId)
                        .update()
                == 1;
    }

    public boolean delete(long memberId, long itemId) {
        return jdbc.sql("DELETE FROM cart_items WHERE member_id=:member AND id=:id")
                        .param("member", memberId)
                        .param("id", itemId)
                        .update()
                == 1;
    }
}
