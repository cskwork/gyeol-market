package io.github.cskwork.lunepick.cart.internal.domain;

import static org.assertj.core.api.Assertions.*;

import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class QuantityTest {
    @Test
    void acceptsOnlyIntegerQuantitiesBetweenOneAnd99() {
        assertThat(Quantity.of(new BigDecimal("1.00"))).isEqualTo(1);
        assertThat(Quantity.of(new BigDecimal("99"))).isEqualTo(99);
        for (String value : new String[] {"0", "100", "-1", "1.5", "99999999999999999999"}) {
            assertThatThrownBy(() -> Quantity.of(new BigDecimal(value)))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            e -> {
                                assertThat(e.status()).isEqualTo(400);
                                assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                            });
        }
        assertThatThrownBy(() -> Quantity.of(null)).isInstanceOf(ApiException.class);
    }
}
