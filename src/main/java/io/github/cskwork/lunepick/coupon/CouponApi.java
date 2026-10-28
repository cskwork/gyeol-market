package io.github.cskwork.lunepick.coupon;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Member coupon ownership and order reservation contract. */
public interface CouponApi {
    List<CouponView> list(long memberId);

    Discount reserve(long memberId, long memberCouponId, long orderId, BigDecimal subtotal);

    void redeem(long memberId, long memberCouponId, long orderId);

    void release(long memberId, long memberCouponId, long orderId);

    long usableCount(long memberId);

    record Discount(BigDecimal amount, boolean freeShipping) {}

    record CouponView(
            long id,
            long couponId,
            String status,
            Instant issuedAt,
            Instant expiresAt,
            String couponName,
            String discountType,
            BigDecimal discountValue,
            BigDecimal minOrderAmount) {}
}
