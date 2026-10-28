package io.github.cskwork.lunepick.order.internal.web;

import io.github.cskwork.lunepick.order.internal.application.OrderDtos.*;
import io.github.cskwork.lunepick.order.internal.application.OrderService;
import io.github.cskwork.lunepick.shared.ApiResponse;
import io.github.cskwork.lunepick.shared.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @Operation(summary = "주문 생성 및 재고·쿠폰·포인트 예약")
    @PostMapping("/orders")
    public ApiResponse<CreateResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody CreateRequest body) {
        return ApiResponse.ok(orders.create(Long.parseLong(jwt.getSubject()), key, body));
    }

    @Operation(summary = "내 주문 목록")
    @GetMapping("/orders")
    public ApiResponse<List<ListItem>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        var result =
                orders.list(
                        Long.parseLong(jwt.getSubject()), status, PageQuery.from(page, size, 10));
        return ApiResponse.page(result.items(), result.meta());
    }

    @Operation(summary = "내 주문 상세 및 주문 당시 배송 정보")
    @GetMapping("/orders/{orderNo}")
    public ApiResponse<Detail> detail(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String orderNo) {
        return ApiResponse.ok(orders.detail(Long.parseLong(jwt.getSubject()), orderNo));
    }

    @Operation(summary = "내 포인트·쿠폰·주문 요약")
    @GetMapping("/me/summary")
    public ApiResponse<Summary> summary(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(orders.summary(Long.parseLong(jwt.getSubject())));
    }
}
