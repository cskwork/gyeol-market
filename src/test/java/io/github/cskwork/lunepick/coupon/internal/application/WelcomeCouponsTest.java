package io.github.cskwork.lunepick.coupon.internal.application;

import static org.mockito.Mockito.*;

import io.github.cskwork.lunepick.member.MemberRegistered;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WelcomeCouponsTest {
    @Test
    void registrationIssuesTheWelcomeGrantUsingTheClockAndNinetyDayExpiry() {
        var store = mock(CouponStore.class);
        var now = Instant.parse("2026-10-28T00:00:00Z");
        var listener = new WelcomeCoupons(store, Clock.fixed(now, ZoneOffset.UTC));

        listener.on(new MemberRegistered(7));

        verify(store).ensureIssued(7, now, now.plus(Duration.ofDays(90)));
        verifyNoMoreInteractions(store);
    }
}
