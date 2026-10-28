package io.github.cskwork.lunepick.payment.internal.application;

import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Provider operations must be idempotent by payment key, including retries after local rollback.
 * Adapters distinguish a definite decline from an unknown outcome such as a timeout.
 */
public interface PaymentGateway {
    void confirm(String paymentKey, String orderNo, BigDecimal amount);

    void cancel(String paymentKey, String orderNo, BigDecimal amount);

    enum Outcome {
        DECLINED,
        UNKNOWN
    }

    final class Failure extends ApiException {
        private final Outcome outcome;

        private Failure(Outcome outcome, Throwable cause) {
            super(
                    outcome == Outcome.DECLINED ? 502 : 503,
                    outcome == Outcome.DECLINED
                            ? "PAYMENT_PROVIDER_REJECTED"
                            : "PAYMENT_PROVIDER_UNKNOWN",
                    outcome == Outcome.DECLINED
                            ? "결제 승인이 거절되었습니다."
                            : "결제 결과를 확인하고 있습니다. 잠시 후 주문 내역을 확인해주세요.");
            this.outcome = Objects.requireNonNull(outcome);
            if (cause != null) initCause(cause);
        }

        public static Failure declined() {
            return new Failure(Outcome.DECLINED, null);
        }

        public static Failure unknown(Throwable cause) {
            return new Failure(Outcome.UNKNOWN, cause);
        }

        public Outcome outcome() {
            return outcome;
        }
    }
}
