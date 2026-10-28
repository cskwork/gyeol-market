package io.github.cskwork.lunepick.coupon.internal.web;

import io.github.cskwork.lunepick.coupon.CouponApi;
import io.github.cskwork.lunepick.coupon.CouponApi.CouponView;
import io.github.cskwork.lunepick.shared.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CouponController {
    private final CouponApi coupons;

    CouponController(CouponApi coupons) {
        this.coupons = coupons;
    }

    @GetMapping("/api/me/coupons")
    @Operation(summary = "내 쿠폰 목록", description = "로그인 회원에게 최초 1회 지급한 쿠폰의 상태와 할인 조건을 조회합니다.")
    ApiResponse<List<CouponView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(coupons.list(Long.parseLong(jwt.getSubject())));
    }
}
