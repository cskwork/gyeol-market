package io.github.cskwork.lunepick.coupon.internal.application;

import io.github.cskwork.lunepick.coupon.CouponApi.CouponView;
import io.github.cskwork.lunepick.coupon.internal.domain.MemberCoupon;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CouponStore {
    void ensureIssued(long memberId, Instant issuedAt, Instant expiresAt);

    List<CouponView> list(long memberId);

    Optional<MemberCoupon> lock(long memberId, long memberCouponId);

    void reserve(long memberId, long memberCouponId, long orderId);

    boolean redeem(long memberId, long memberCouponId, long orderId);

    void release(long memberId, long memberCouponId, long orderId);

    long usableCount(long memberId);
}
