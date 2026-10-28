package io.github.cskwork.lunepick.cart.internal.web;

import io.github.cskwork.lunepick.cart.internal.application.*;
import io.github.cskwork.lunepick.shared.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
public class CartController {
    private final CartService cart;

    public CartController(CartService cart) {
        this.cart = cart;
    }

    @GetMapping
    @Operation(summary = "내 장바구니", description = "인증 회원의 상품 옵션 가격과 수량을 조회합니다.")
    ApiResponse<CartViews.Cart> view(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(cart.view(Long.parseLong(jwt.getSubject())));
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "장바구니 담기", description = "같은 옵션의 수량을 합산하며 최대 99개로 제한합니다.")
    ApiResponse<CartViews.Added> add(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CartViews.AddRequest request) {
        return ApiResponse.ok(
                cart.add(
                        Long.parseLong(jwt.getSubject()),
                        request.productOptionId(),
                        request.quantity()));
    }

    @PatchMapping("/items/{id}")
    @Operation(summary = "수량 변경", description = "내 장바구니 상품의 정수 수량을 1~99개로 변경합니다.")
    ApiResponse<Void> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @Valid @RequestBody CartViews.UpdateRequest request) {
        cart.update(Long.parseLong(jwt.getSubject()), id, request.quantity());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/items/{id}")
    @Operation(summary = "장바구니 삭제", description = "인증 회원 소유의 장바구니 상품을 삭제합니다.")
    ApiResponse<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        cart.remove(Long.parseLong(jwt.getSubject()), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/merge")
    @Operation(summary = "비회원 장바구니 병합", description = "비회원 장바구니 저장 기능이 없어 병합 건수 0을 반환합니다.")
    ApiResponse<CartViews.Merged> merge(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CartViews.MergeRequest request) {
        return ApiResponse.ok(cart.merge(Long.parseLong(jwt.getSubject()), request.sessionId()));
    }
}
