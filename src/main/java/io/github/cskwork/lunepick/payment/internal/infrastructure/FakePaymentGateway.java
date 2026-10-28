package io.github.cskwork.lunepick.payment.internal.infrastructure;

import io.github.cskwork.lunepick.payment.internal.application.PaymentGateway;
import java.math.BigDecimal;

/** Local/test adapter. No money is moved; production must supply a real provider adapter. */
public final class FakePaymentGateway implements PaymentGateway {
    @Override
    public void confirm(String key, String orderNo, BigDecimal amount) {}

    @Override
    public void cancel(String key, String orderNo, BigDecimal amount) {}
}
