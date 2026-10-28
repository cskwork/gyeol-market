package io.github.cskwork.lunepick.cart.internal.domain;

import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;

public final class Quantity {
    private Quantity() {}

    public static int of(BigDecimal value) {
        try {
            int quantity = value.intValueExact();
            if (quantity >= 1 && quantity <= 99) return quantity;
        } catch (ArithmeticException | NullPointerException invalid) {
            /* report the same public rule */
        }
        throw new ApiException(400, "VALIDATION_FAILED", "수량은 1~99개까지 가능합니다.");
    }
}
