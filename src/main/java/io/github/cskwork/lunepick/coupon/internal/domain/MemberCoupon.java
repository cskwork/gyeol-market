package io.github.cskwork.lunepick.coupon.internal.domain;

import io.github.cskwork.lunepick.coupon.CouponApi.CouponView;
import io.github.cskwork.lunepick.coupon.CouponApi.Discount;
import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.time.Instant;

public record MemberCoupon(CouponView view, Long reservedOrderId) {
    public Discount discount(BigDecimal subtotal, Instant now) {
        if (!view.status().equals("ISSUED")
                || !view.expiresAt().isAfter(now)
                || reservedOrderId != null) {
            throw invalid();
        }
        if (subtotal.compareTo(view.minOrderAmount()) < 0) {
            throw new ApiException(400, "COUPON_INVALID", "쿠폰 최소 주문 금액을 충족하지 않습니다.");
        }
        BigDecimal amount =
                switch (view.discountType()) {
                    case "FIXED_AMOUNT" -> view.discountValue().min(subtotal);
                    case "PERCENTAGE" -> subtotal.multiply(view.discountValue()).movePointLeft(2);
                    case "FREE_SHIPPING" -> BigDecimal.ZERO;
                    default -> throw invalid();
                };
        return new Discount(amount, view.discountType().equals("FREE_SHIPPING"));
    }

    public static ApiException invalid() {
        return new ApiException(400, "COUPON_INVALID", "사용할 수 없는 쿠폰입니다.");
    }
}
