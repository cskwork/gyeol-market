package io.github.cskwork.lunepick.order.internal.domain;

import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;

public record OrderPricing(
        BigDecimal subtotal,
        BigDecimal rawDiscount,
        BigDecimal discount,
        BigDecimal points,
        BigDecimal shipping,
        BigDecimal total) {
    public static OrderPricing calculate(
            BigDecimal subtotal, BigDecimal discount, boolean freeShipping, BigDecimal points) {
        validatePoints(points);
        BigDecimal shipping =
                !freeShipping && subtotal.compareTo(new BigDecimal("30000")) < 0
                        ? new BigDecimal("3000")
                        : BigDecimal.ZERO;
        BigDecimal payable = subtotal.subtract(discount).add(shipping);
        if (points.compareTo(payable) > 0)
            throw new ApiException(400, "INSUFFICIENT_POINTS", "포인트는 주문금액을 초과할 수 없습니다.");
        return new OrderPricing(
                subtotal,
                discount,
                discount.setScale(0, RoundingMode.HALF_UP),
                points,
                shipping,
                payable.subtract(points).max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP));
    }

    public static void validatePoints(BigDecimal points) {
        if (points == null || points.signum() < 0 || points.stripTrailingZeros().scale() > 0)
            throw new ApiException(400, "INSUFFICIENT_POINTS", "보유 포인트 범위 안에서 정수로 입력해주세요.");
    }

    public static int quantity(BigDecimal quantity) {
        if (quantity == null
                || quantity.stripTrailingZeros().scale() > 0
                || quantity.compareTo(BigDecimal.ONE) < 0
                || quantity.compareTo(new BigDecimal("99")) > 0)
            throw new ApiException(400, "VALIDATION_FAILED", "수량은 1~99개까지 가능합니다.");
        return quantity.intValueExact();
    }

    public static void requirePayment(
            String expectedKey, BigDecimal expectedAmount, String key, BigDecimal amount) {
        if (!expectedKey.equals(key) || amount == null || expectedAmount.compareTo(amount) != 0)
            throw new ApiException(422, "PAYMENT_AMOUNT_MISMATCH", "결제 금액이 주문 금액과 일치하지 않습니다.");
    }

    public static void requirePending(String status) {
        if (!"PAYMENT_PENDING".equals(status))
            throw new ApiException(409, "INVALID_ORDER_STATE", "주문 상태를 변경할 수 없습니다.");
    }
}
