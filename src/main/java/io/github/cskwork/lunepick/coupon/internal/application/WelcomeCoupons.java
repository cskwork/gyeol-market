package io.github.cskwork.lunepick.coupon.internal.application;

import io.github.cskwork.lunepick.member.MemberRegistered;
import java.time.Clock;
import java.time.Duration;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class WelcomeCoupons {
    private final CouponStore store;
    private final Clock clock;

    public WelcomeCoupons(CouponStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void on(MemberRegistered event) {
        var now = clock.instant();
        // The unique member/coupon key preserves the original grant on outbox replay.
        store.ensureIssued(event.memberId(), now, now.plus(Duration.ofDays(90)));
    }
}
