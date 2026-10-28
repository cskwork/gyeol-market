package io.github.cskwork.lunepick.coupon.internal.web;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cskwork.lunepick.coupon.CouponApi;
import io.github.cskwork.lunepick.coupon.CouponApi.CouponView;
import io.github.cskwork.lunepick.shared.internal.web.ApiExceptionHandler;
import io.github.cskwork.lunepick.shared.internal.web.SecurityConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CouponController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class CouponControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CouponApi coupons;
    @MockitoBean JwtDecoder decoder;

    @Test
    void listUsesAuthenticatedSubjectAndReturnsAllCheckoutFields() throws Exception {
        when(coupons.list(7))
                .thenReturn(
                        List.of(
                                new CouponView(
                                        12,
                                        1,
                                        "ISSUED",
                                        Instant.parse("2026-10-28T00:00:00Z"),
                                        Instant.parse("2027-01-26T00:00:00Z"),
                                        "신규 회원 3000원 쿠폰",
                                        "FIXED_AMOUNT",
                                        new BigDecimal("3000"),
                                        new BigDecimal("10000"))));
        mvc.perform(
                        get("/api/me/coupons")
                                .param("memberId", "99")
                                .with(jwt().jwt(jwt -> jwt.subject("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(12))
                .andExpect(jsonPath("$.data[0].couponId").value(1))
                .andExpect(jsonPath("$.data[0].status").value("ISSUED"))
                .andExpect(jsonPath("$.data[0].issuedAt").value("2026-10-28T00:00:00Z"))
                .andExpect(jsonPath("$.data[0].expiresAt").value("2027-01-26T00:00:00Z"))
                .andExpect(jsonPath("$.data[0].couponName").value("신규 회원 3000원 쿠폰"))
                .andExpect(jsonPath("$.data[0].discountType").value("FIXED_AMOUNT"))
                .andExpect(jsonPath("$.data[0].discountValue").value(3000))
                .andExpect(jsonPath("$.data[0].minOrderAmount").value(10000))
                .andExpect(jsonPath("$.data[0].reservedOrderId").doesNotExist());
        verify(coupons).list(7);
    }

    @Test
    void unauthenticatedListReturns401() throws Exception {
        mvc.perform(get("/api/me/coupons"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(coupons);
    }
}
