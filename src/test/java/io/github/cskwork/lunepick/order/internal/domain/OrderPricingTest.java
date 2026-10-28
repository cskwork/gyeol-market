package io.github.cskwork.lunepick.order.internal.domain;

import static org.assertj.core.api.Assertions.*;

import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderPricingTest {
    private BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    @ParameterizedTest
    @CsvSource({
        "15000,0,15000",
        "15000,5000,10000",
        "15000,15000,0",
        "30000,5000,25000",
        "30001,5000,25001"
    })
    void freeShippingCombinesWithPointsAndUsesSubtotalBeforePoints(
            BigDecimal subtotal, BigDecimal points, BigDecimal total) {
        var result = OrderPricing.calculate(subtotal, BigDecimal.ZERO, true, points);
        assertThat(result.shipping()).isZero();
        assertThat(result.discount()).isZero();
        assertThat(result.total()).isEqualByComparingTo(total);
        assertThatThrownBy(
                        () ->
                                OrderPricing.calculate(
                                        subtotal,
                                        BigDecimal.ZERO,
                                        true,
                                        subtotal.add(BigDecimal.ONE)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INSUFFICIENT_POINTS"));
    }

    @Test
    void usesRawDiscountForTotalAndRoundsOnlyDisplayedDiscountAndFinalAmount() {
        var result = OrderPricing.calculate(n("10001"), n("1000.5"), false, n("100"));
        assertThat(result.discount()).isEqualByComparingTo("1001");
        assertThat(result.rawDiscount()).isEqualByComparingTo("1000.5");
        assertThat(result.total()).isEqualByComparingTo("11901");
        assertThat(result.shipping()).isEqualByComparingTo("3000");
    }

    @Test
    void shippingThresholdIsInclusiveAndFreeShippingWaivesFee() {
        assertThat(OrderPricing.calculate(n("30000"), n("0"), false, n("0")).shipping()).isZero();
        assertThat(OrderPricing.calculate(n("29999"), n("0"), true, n("0")).shipping()).isZero();
        assertThat(OrderPricing.calculate(n("29999"), n("0"), false, n("0")).shipping())
                .isEqualByComparingTo("3000");
        assertThat(OrderPricing.calculate(n("10000"), n("0"), false, n("13000")).total()).isZero();
    }

    @Test
    void quantityNeverCoercesFractionalOrInvalidValues() {
        for (String input : new String[] {"0", "100", "1.5", "-1"})
            assertThatThrownBy(() -> OrderPricing.quantity(n(input))).hasMessageContaining("수량");
        assertThatThrownBy(() -> OrderPricing.quantity(null)).hasMessageContaining("수량");
        assertThat(OrderPricing.quantity(n("1.0"))).isEqualTo(1);
        assertThat(OrderPricing.quantity(n("99"))).isEqualTo(99);
    }

    @Test
    void pointsMustBeIntegerNonnegativeAndCannotExceedUnroundedPayable() {
        for (BigDecimal points : new BigDecimal[] {null, n("-1"), n("0.1")})
            assertThatThrownBy(() -> OrderPricing.validatePoints(points))
                    .hasMessageContaining("정수");
        assertThatThrownBy(() -> OrderPricing.calculate(n("10001"), n("1000.5"), false, n("12001")))
                .hasMessageContaining("초과");
    }

    @Test
    void paymentIdentityAndTerminalStateAreChecked() {
        OrderPricing.requirePayment("1", n("100"), "1", n("100.0"));
        assertThatThrownBy(() -> OrderPricing.requirePayment("1", n("100"), "2", n("100")))
                .hasMessageContaining("일치");
        assertThatThrownBy(() -> OrderPricing.requirePayment("1", n("100"), "1", null))
                .hasMessageContaining("일치");
        assertThatThrownBy(() -> OrderPricing.requirePayment("1", n("100"), "1", n("101")))
                .hasMessageContaining("일치");
        OrderPricing.requirePending("PAYMENT_PENDING");
        assertThatThrownBy(() -> OrderPricing.requirePending("FAILED")).hasMessageContaining("상태");
    }
}
