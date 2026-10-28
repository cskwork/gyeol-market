package io.github.cskwork.lunepick.coupon.internal.infrastructure;

import io.github.cskwork.lunepick.coupon.CouponApi.CouponView;
import io.github.cskwork.lunepick.coupon.internal.application.CouponStore;
import io.github.cskwork.lunepick.coupon.internal.domain.MemberCoupon;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCouponStore implements CouponStore {
    private static final String SELECT =
            """
            SELECT mc.id, mc.coupon_id, mc.status, mc.issued_at, mc.expires_at, mc.reserved_order_id,
                   c.name AS coupon_name, c.discount_type, c.discount_value, c.min_order_amount
            FROM member_coupon mc JOIN coupon c ON c.id = mc.coupon_id
            """;
    private final JdbcClient jdbc;

    JdbcCouponStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureIssued(long memberId, Instant issuedAt, Instant expiresAt) {
        jdbc.sql(
                        """
                        INSERT INTO member_coupon (member_id, coupon_id, status, issued_at, expires_at)
                        SELECT :member, id, 'ISSUED', :issuedAt, :expiresAt FROM coupon WHERE id IN (1, 2, 3) ORDER BY id
                        ON CONFLICT (member_id, coupon_id) DO NOTHING
                        """)
                .param("member", memberId)
                .param("issuedAt", Timestamp.from(issuedAt))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    public List<CouponView> list(long memberId) {
        return jdbc
                .sql(SELECT + " WHERE mc.member_id = :member ORDER BY mc.coupon_id")
                .param("member", memberId)
                .query(CouponRow.class)
                .list()
                .stream()
                .map(CouponRow::view)
                .toList();
    }

    public Optional<MemberCoupon> lock(long memberId, long memberCouponId) {
        return jdbc.sql(SELECT + " WHERE mc.member_id = :member AND mc.id = :id FOR UPDATE OF mc")
                .param("member", memberId)
                .param("id", memberCouponId)
                .query(CouponRow.class)
                .optional()
                .map(row -> new MemberCoupon(row.view(), row.reservedOrderId()));
    }

    public void reserve(long memberId, long memberCouponId, long orderId) {
        int changed =
                jdbc.sql(
                                """
                                UPDATE member_coupon SET reserved_order_id = :orderId
                                WHERE member_id = :member AND id = :id AND status = 'ISSUED' AND reserved_order_id IS NULL
                                """)
                        .param("orderId", orderId)
                        .param("member", memberId)
                        .param("id", memberCouponId)
                        .update();
        if (changed != 1) throw MemberCoupon.invalid();
    }

    public boolean redeem(long memberId, long memberCouponId, long orderId) {
        // The reservation remains as an idempotency marker after payment.
        return jdbc.sql(
                                """
                                UPDATE member_coupon SET status = 'USED'
                                WHERE member_id = :member AND id = :id AND reserved_order_id = :orderId AND status IN ('ISSUED', 'USED')
                                """)
                        .param("member", memberId)
                        .param("id", memberCouponId)
                        .param("orderId", orderId)
                        .update()
                == 1;
    }

    public void release(long memberId, long memberCouponId, long orderId) {
        jdbc.sql(
                        """
                        UPDATE member_coupon SET reserved_order_id = NULL
                        WHERE member_id = :member AND id = :id AND reserved_order_id = :orderId AND status = 'ISSUED'
                        """)
                .param("member", memberId)
                .param("id", memberCouponId)
                .param("orderId", orderId)
                .update();
    }

    public long usableCount(long memberId) {
        return jdbc.sql(
                        "SELECT count(*) FROM member_coupon WHERE member_id = :member AND status ="
                                + " 'ISSUED'")
                .param("member", memberId)
                .query(Long.class)
                .single();
    }

    private record CouponRow(
            long id,
            long couponId,
            String status,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt,
            Long reservedOrderId,
            String couponName,
            String discountType,
            BigDecimal discountValue,
            BigDecimal minOrderAmount) {
        CouponView view() {
            return new CouponView(
                    id,
                    couponId,
                    status,
                    issuedAt.toInstant(),
                    expiresAt.toInstant(),
                    couponName,
                    discountType,
                    discountValue,
                    minOrderAmount);
        }
    }
}
