package io.github.cskwork.lunepick.coupon.internal.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.cskwork.lunepick.coupon.CouponApi.CouponView;
import io.github.cskwork.lunepick.coupon.internal.domain.MemberCoupon;
import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CouponServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private final CouponStore store = mock(CouponStore.class);
    private final CouponService service =
            new CouponService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    private final CouponView view =
            new CouponView(
                    10,
                    1,
                    "ISSUED",
                    NOW,
                    NOW.plus(Duration.ofDays(90)),
                    "신규 회원 3000원 쿠폰",
                    "FIXED_AMOUNT",
                    new BigDecimal("3000"),
                    new BigDecimal("10000"));

    @BeforeEach
    void arrange() {
        when(store.lock(7, 10)).thenReturn(Optional.of(new MemberCoupon(view, null)));
    }

    @Test
    void listOnlyReadsAlreadyIssuedCoupons() {
        when(store.list(7)).thenReturn(List.of(view));
        assertThat(service.list(7)).containsExactly(view);
        verify(store).list(7);
        verify(store, never()).ensureIssued(anyLong(), any(), any());
    }

    @Test
    void reserveLocksOwnershipAndValidatesBeforePersistingTheReservation() {
        assertThat(service.reserve(7, 10, 100, new BigDecimal("10000")).amount())
                .isEqualByComparingTo("3000");
        var ordered = inOrder(store);
        ordered.verify(store).lock(7, 10);
        ordered.verify(store).reserve(7, 10, 100);
        verify(store, never()).ensureIssued(anyLong(), any(), any());
    }

    @Test
    void anotherMembersCouponOrMissingCouponIsInvalid() {
        when(store.lock(8, 10)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reserve(8, 10, 100, new BigDecimal("10000")))
                .isInstanceOf(ApiException.class)
                .hasMessage("사용할 수 없는 쿠폰입니다.");
        verify(store, never()).reserve(anyLong(), anyLong(), anyLong());
    }

    @Test
    void expiredCouponDoesNotWriteAReservation() {
        var expired =
                new CouponView(
                        10,
                        1,
                        "ISSUED",
                        NOW.minus(Duration.ofDays(90)),
                        NOW,
                        view.couponName(),
                        view.discountType(),
                        view.discountValue(),
                        view.minOrderAmount());
        when(store.lock(7, 10)).thenReturn(Optional.of(new MemberCoupon(expired, null)));
        assertThatThrownBy(() -> service.reserve(7, 10, 100, new BigDecimal("10000")))
                .isInstanceOf(ApiException.class);
        verify(store, never()).reserve(anyLong(), anyLong(), anyLong());
    }

    @Test
    void anExistingReservationDoesNotWriteAnotherOne() {
        when(store.lock(7, 10)).thenReturn(Optional.of(new MemberCoupon(view, 99L)));
        assertThatThrownBy(() -> service.reserve(7, 10, 100, new BigDecimal("10000")))
                .isInstanceOf(ApiException.class);
        verify(store, never()).reserve(anyLong(), anyLong(), anyLong());
    }

    @Test
    void redeemUsesTheExactMemberCouponAndOrder() {
        when(store.redeem(7, 10, 100)).thenReturn(true);
        service.redeem(7, 10, 100);
        verify(store).redeem(7, 10, 100);
    }

    @Test
    void redeemWithoutMatchingReservationFails() {
        assertThatThrownBy(() -> service.redeem(7, 10, 101))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(400);
                            assertThat(error.code()).isEqualTo("COUPON_INVALID");
                        });
    }

    @Test
    void releaseIsScopedToTheOwningMemberAndPendingOrder() {
        service.release(7, 10, 100);
        verify(store).release(7, 10, 100);
    }

    @Test
    void usableCountOnlyReadsAndKeepsMockStatusSemantics() {
        when(store.usableCount(7)).thenReturn(3L);
        assertThat(service.usableCount(7)).isEqualTo(3);
        verify(store).usableCount(7);
        verify(store, never()).ensureIssued(anyLong(), any(), any());
    }
}
