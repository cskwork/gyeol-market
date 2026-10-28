package io.github.cskwork.lunepick.coupon.internal.domain;

import static org.assertj.core.api.Assertions.*;

import io.github.cskwork.lunepick.coupon.CouponApi.CouponView;
import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MemberCouponTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");

    @Test
    void fixedAmountIsCappedAtProductSubtotal() {
        assertThat(
                        coupon("FIXED_AMOUNT", "3000", "0", "ISSUED", NOW.plusSeconds(1), null)
                                .discount(new BigDecimal("2500"), NOW)
                                .amount())
                .isEqualByComparingTo("2500");
    }

    @Test
    void fixedAmountUsesItsFullValueAboveMinimum() {
        var result =
                coupon("FIXED_AMOUNT", "3000", "10000", "ISSUED", NOW.plusSeconds(1), null)
                        .discount(new BigDecimal("10000"), NOW);
        assertThat(result.amount()).isEqualByComparingTo("3000");
        assertThat(result.freeShipping()).isFalse();
    }

    @Test
    void percentagePreservesFractionUntilOrderPricingRoundsTheFinalTotal() {
        var result =
                coupon("PERCENTAGE", "10", "30000", "ISSUED", NOW.plusSeconds(1), null)
                        .discount(new BigDecimal("30005"), NOW);
        assertThat(result.amount()).isEqualByComparingTo("3000.5");
        assertThat(result.freeShipping()).isFalse();
    }

    @Test
    void shippingCouponWaivesShippingWithoutReducingProductSubtotal() {
        var result =
                coupon("FREE_SHIPPING", "0", "15000", "ISSUED", NOW.plusSeconds(1), null)
                        .discount(new BigDecimal("15000"), NOW);
        assertThat(result.amount()).isZero();
        assertThat(result.freeShipping()).isTrue();
    }

    @Test
    void shippingCouponRequiresItsMinimumBeforeCheckoutAppliesPoints() {
        assertThatThrownBy(
                        () ->
                                coupon(
                                                "FREE_SHIPPING",
                                                "0",
                                                "15000",
                                                "ISSUED",
                                                NOW.plusSeconds(1),
                                                null)
                                        .discount(new BigDecimal("14999"), NOW))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo("COUPON_INVALID");
                            assertThat(error.getMessage()).isEqualTo("쿠폰 최소 주문 금액을 충족하지 않습니다.");
                        });
    }

    @Test
    void belowMinimumUsesTheFrontendMessage() {
        assertThatThrownBy(
                        () ->
                                coupon(
                                                "FIXED_AMOUNT",
                                                "3000",
                                                "10000",
                                                "ISSUED",
                                                NOW.plusSeconds(1),
                                                null)
                                        .discount(new BigDecimal("9999"), NOW))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(400);
                            assertThat(error.code()).isEqualTo("COUPON_INVALID");
                            assertThat(error.getMessage()).isEqualTo("쿠폰 최소 주문 금액을 충족하지 않습니다.");
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"USED", "EXPIRED", "REVOKED"})
    void unavailableStatusCannotReserve(String status) {
        assertInvalid(coupon("FIXED_AMOUNT", "3000", "10000", status, NOW.plusSeconds(1), null));
    }

    @Test
    void exactExpiryAndPastExpiryCannotReserve() {
        assertInvalid(coupon("FIXED_AMOUNT", "3000", "10000", "ISSUED", NOW, null));
        assertInvalid(coupon("FIXED_AMOUNT", "3000", "10000", "ISSUED", NOW.minusSeconds(1), null));
    }

    @Test
    void aPendingOrderReservationPreventsReuse() {
        assertInvalid(coupon("FIXED_AMOUNT", "3000", "10000", "ISSUED", NOW.plusSeconds(1), 10L));
    }

    @Test
    void anUnknownDiscountTypeFailsClosed() {
        assertInvalid(coupon("UNKNOWN", "3000", "10000", "ISSUED", NOW.plusSeconds(1), null));
    }

    private void assertInvalid(MemberCoupon coupon) {
        assertThatThrownBy(() -> coupon.discount(new BigDecimal("30000"), NOW))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(400);
                            assertThat(error.code()).isEqualTo("COUPON_INVALID");
                            assertThat(error.getMessage()).isEqualTo("사용할 수 없는 쿠폰입니다.");
                        });
    }

    private MemberCoupon coupon(
            String type,
            String value,
            String minimum,
            String status,
            Instant expiry,
            Long orderId) {
        return new MemberCoupon(
                new CouponView(
                        1,
                        1,
                        status,
                        NOW.minusSeconds(10),
                        expiry,
                        "쿠폰",
                        type,
                        new BigDecimal(value),
                        new BigDecimal(minimum)),
                orderId);
    }
}
