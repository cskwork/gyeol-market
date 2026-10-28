package io.github.cskwork.lunepick.coupon.internal.application;

import io.github.cskwork.lunepick.coupon.CouponApi;
import io.github.cskwork.lunepick.coupon.internal.domain.MemberCoupon;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CouponService implements CouponApi {
    private final CouponStore store;
    private final Clock clock;

    public CouponService(CouponStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CouponView> list(long memberId) {
        return store.list(memberId);
    }

    public Discount reserve(long memberId, long memberCouponId, long orderId, BigDecimal subtotal) {
        var coupon = store.lock(memberId, memberCouponId).orElseThrow(MemberCoupon::invalid);
        Discount discount = coupon.discount(subtotal, clock.instant());
        store.reserve(memberId, memberCouponId, orderId);
        return discount;
    }

    public void redeem(long memberId, long memberCouponId, long orderId) {
        if (!store.redeem(memberId, memberCouponId, orderId)) throw MemberCoupon.invalid();
    }

    public void release(long memberId, long memberCouponId, long orderId) {
        store.release(memberId, memberCouponId, orderId);
    }

    @Transactional(readOnly = true)
    public long usableCount(long memberId) {
        return store.usableCount(memberId);
    }
}
