package io.github.cskwork.lunepick.order.internal.persistence;

import io.github.cskwork.lunepick.address.AddressApi;
import io.github.cskwork.lunepick.catalog.CatalogStockApi.OptionSnapshot;
import io.github.cskwork.lunepick.order.internal.application.OrderDtos.*;
import io.github.cskwork.lunepick.order.internal.config.OrderExpiryRetryProperties;
import io.github.cskwork.lunepick.order.internal.domain.OrderPricing;
import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.PageQuery;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderStore {
    private final JdbcClient jdbc;

    public OrderStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long nextPaymentKey() {
        return jdbc.sql("select nextval('order_payment_key_seq')").query(Long.class).single();
    }

    public Optional<Row> insertAttempt(long memberId, String key, String canonical, Instant now) {
        // ON CONFLICT waits for the winning transaction; a rollback permits this insert to win
        // instead.
        return jdbc.sql(
                        "insert into orders(member_id,idempotency_key,canonical_request,created_at)"
                                + " values(?,?,?,?) on conflict(member_id,idempotency_key) do nothing"
                                + " returning *")
                .params(memberId, key, canonical, Timestamp.from(now))
                .query(this::row)
                .optional();
    }

    public List<Row> expiredPending(Instant cutoff, Instant now, int batchSize) {
        return jdbc.sql(
                        """
                        SELECT o.* FROM orders o
                        WHERE o.status='PAYMENT_PENDING' AND o.created_at<=:cutoff
                          AND (o.expiry_next_attempt_at IS NULL OR o.expiry_next_attempt_at<=:now)
                          AND NOT EXISTS (SELECT 1 FROM order_reconciliation_holds h WHERE h.order_id=o.id)
                        ORDER BY o.created_at,o.id LIMIT :batch FOR UPDATE OF o SKIP LOCKED
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .param("now", Timestamp.from(now))
                .param("batch", batchSize)
                .query(this::row)
                .list();
    }

    public void holdForReconciliation(long orderId, String reason, Instant now) {
        jdbc.sql(
                        "INSERT INTO order_reconciliation_holds(order_id,reason,created_at) VALUES(:id,:reason,:now) ON CONFLICT DO NOTHING")
                .param("id", orderId)
                .param("reason", reason)
                .param("now", Timestamp.from(now))
                .update();
    }

    public boolean reconciliationHeld(long orderId) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM order_reconciliation_holds WHERE order_id=:id)")
                .param("id", orderId)
                .query(Boolean.class)
                .single();
    }

    public Optional<ExpiryFailure> recordExpiryFailure(
            long orderId, Instant now, String error, OrderExpiryRetryProperties retry) {
        var attempts =
                jdbc.sql(
                                "SELECT expiry_attempts FROM orders WHERE id=:id AND status='PAYMENT_PENDING' FOR UPDATE")
                        .param("id", orderId)
                        .query(Integer.class)
                        .optional();
        if (attempts.isEmpty()) return Optional.empty();
        int nextAttempt = Math.addExact(attempts.get(), 1);
        Instant nextAt = now.plus(retry.backoff(nextAttempt));
        jdbc.sql(
                        "UPDATE orders SET expiry_attempts=:attempts, expiry_next_attempt_at=:next, expiry_last_error=:error WHERE id=:id")
                .param("id", orderId)
                .param("attempts", nextAttempt)
                .param("next", Timestamp.from(nextAt))
                .param("error", error)
                .update();
        return Optional.of(new ExpiryFailure(nextAttempt, nextAt));
    }

    public record ExpiryFailure(int attempts, Instant nextAttemptAt) {}

    public int forgetExpiredReplayKeys(Instant cutoff) {
        return jdbc.sql(
                        "update orders set idempotency_key=null,canonical_request=null where"
                                + " status<>'PAYMENT_PENDING' and created_at<? and idempotency_key is"
                                + " not null")
                .params(Timestamp.from(cutoff))
                .update();
    }

    public Row byKey(long memberId, String key) {
        return jdbc.sql("select * from orders where member_id=? and idempotency_key=?")
                .params(memberId, key)
                .query(this::row)
                .optional()
                .orElseThrow();
    }

    public Row byOrderNo(String orderNo, boolean lock) {
        return jdbc.sql("select * from orders where order_no=?" + (lock ? " for update" : ""))
                .params(orderNo)
                .query(this::row)
                .optional()
                .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
    }

    public void finish(
            long id,
            String no,
            long paymentKey,
            OrderPricing p,
            Long couponId,
            AddressApi.Address a) {
        jdbc.sql(
                        "update orders set"
                                + " order_no=?,payment_key=?,total_product_amount=?,raw_discount_amount=?,discount_amount=?,point_used_amount=?,delivery_fee=?,final_payment_amount=?,member_coupon_id=?,delivery_address_id=?,recipient_name=?,phone=?,zipcode=?,address_main=?,address_detail=?"
                                + " where id=?")
                .params(
                        no,
                        paymentKey,
                        p.subtotal(),
                        p.rawDiscount(),
                        p.discount(),
                        p.points(),
                        p.shipping(),
                        p.total(),
                        couponId,
                        a.id(),
                        a.recipientName(),
                        a.phone(),
                        a.zipcode(),
                        a.addressMain(),
                        a.addressDetail(),
                        id)
                .update();
    }

    public void addItem(long id, int position, OptionSnapshot option, int quantity) {
        jdbc.sql(
                        "insert into"
                                + " order_items(order_id,position,product_id,product_option_id,product_name,option_name,unit_price,quantity,total_amount)"
                                + " values(?,?,?,?,?,?,?,?,?)")
                .params(
                        id,
                        position,
                        option.productId(),
                        option.optionId(),
                        option.productName(),
                        option.optionName(),
                        option.salePrice(),
                        quantity,
                        option.salePrice().multiply(BigDecimal.valueOf(quantity)))
                .update();
    }

    public void saveCartItems(long id, List<Long> cartItemIds) {
        cartItemIds.stream()
                .distinct()
                .forEach(
                        cartId ->
                                jdbc.sql(
                                                "insert into"
                                                        + " order_cart_items(order_id,cart_item_id)"
                                                        + " values(?,?)")
                                        .params(id, cartId)
                                        .update());
    }

    public List<Long> cartItems(long id) {
        return jdbc.sql(
                        "select cart_item_id from order_cart_items where order_id=? order by"
                                + " cart_item_id")
                .params(id)
                .query(Long.class)
                .list();
    }

    public List<StockLine> stockLines(long id) {
        return jdbc.sql(
                        "select product_option_id,sum(quantity) as quantity from order_items where"
                                + " order_id=? group by product_option_id order by product_option_id")
                .params(id)
                .query((rs, n) -> new StockLine(rs.getLong(1), rs.getInt(2)))
                .list();
    }

    public void status(long id, String status) {
        jdbc.sql("update orders set status=? where id=?").params(status, id).update();
    }

    public List<ItemResponse> items(long id) {
        return jdbc.sql("select * from order_items where order_id=? order by position")
                .params(id)
                .query(
                        (rs, n) ->
                                new ItemResponse(
                                        rs.getLong("id"),
                                        rs.getString("product_name"),
                                        rs.getString("option_name"),
                                        rs.getBigDecimal("unit_price"),
                                        rs.getInt("quantity"),
                                        rs.getBigDecimal("total_amount")))
                .list();
    }

    public Detail detail(Row o) {
        return new Detail(
                o.id(),
                o.orderNo(),
                o.status(),
                o.subtotal(),
                o.discount(),
                o.points(),
                o.shipping(),
                o.amount(),
                items(o.id()),
                o.delivery(),
                o.createdAt());
    }

    public long count(long memberId, String status) {
        return jdbc.sql(
                        "select count(*) from orders where member_id=? and (?::text is null or"
                                + " status=?)")
                .params(memberId, status, status)
                .query(Long.class)
                .single();
    }

    public List<ListItem> list(long memberId, String status, PageQuery page) {
        return jdbc.sql(
                        "select * from orders where member_id=? and (?::text is null or status=?)"
                                + " order by created_at desc,id desc limit ? offset ?")
                .params(memberId, status, status, page.limit(), page.offset())
                .query(
                        (rs, n) -> {
                            Row o = row(rs, n);
                            return new ListItem(
                                    o.id(),
                                    o.orderNo(),
                                    o.status(),
                                    o.subtotal(),
                                    o.amount(),
                                    o.createdAt());
                        })
                .list();
    }

    private Row row(ResultSet rs, int index) throws SQLException {
        return new Row(
                rs.getLong("id"),
                rs.getLong("member_id"),
                rs.getString("canonical_request"),
                rs.getString("order_no"),
                rs.getLong("payment_key"),
                rs.getString("status"),
                rs.getBigDecimal("total_product_amount"),
                rs.getBigDecimal("discount_amount"),
                rs.getBigDecimal("point_used_amount"),
                rs.getBigDecimal("delivery_fee"),
                rs.getBigDecimal("final_payment_amount"),
                rs.getObject("member_coupon_id", Long.class),
                new Delivery(
                        rs.getString("recipient_name"),
                        rs.getString("phone"),
                        rs.getString("zipcode"),
                        rs.getString("address_main"),
                        rs.getString("address_detail")),
                rs.getTimestamp("created_at").toInstant());
    }

    public record StockLine(long optionId, int quantity) {}

    public record Row(
            long id,
            long memberId,
            String canonical,
            String orderNo,
            long paymentKey,
            String status,
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal points,
            BigDecimal shipping,
            BigDecimal amount,
            Long couponId,
            Delivery delivery,
            Instant createdAt) {
        public CreateResponse created() {
            return new CreateResponse(orderNo, paymentKey, amount, null);
        }
    }
}
