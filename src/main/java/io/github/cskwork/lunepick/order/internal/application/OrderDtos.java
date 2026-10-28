package io.github.cskwork.lunepick.order.internal.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrderDtos {
    private OrderDtos() {}

    // BigDecimal prevents Jackson's integer coercion from silently accepting quantity=1.5.
    public record ItemRequest(Long productOptionId, BigDecimal quantity) {}

    public record CreateRequest(
            List<ItemRequest> items,
            Long couponId,
            BigDecimal usePointAmount,
            Long deliveryAddressId) {}

    public record CreateResponse(
            String orderNo, long paymentKey, BigDecimal amount, Object pgCheckoutPayload) {}

    public record ItemResponse(
            long id,
            String productName,
            String optionName,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal totalAmount) {}

    public record Delivery(
            String recipientName,
            String phone,
            String zipcode,
            String addressMain,
            String addressDetail) {}

    public record Detail(
            long id,
            String orderNo,
            String status,
            BigDecimal totalProductAmount,
            BigDecimal discountAmount,
            BigDecimal pointUsedAmount,
            BigDecimal deliveryFee,
            BigDecimal finalPaymentAmount,
            List<ItemResponse> items,
            Delivery delivery,
            Instant createdAt) {}

    public record ListItem(
            long id,
            String orderNo,
            String status,
            BigDecimal totalProductAmount,
            BigDecimal finalPaymentAmount,
            Instant createdAt) {}

    public record Summary(
            BigDecimal pointBalance,
            long usableCouponCount,
            long totalOrderCount,
            String gradeName,
            String name) {}
}
