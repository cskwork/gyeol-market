package io.github.cskwork.lunepick.wishlist.internal.web;

import io.github.cskwork.lunepick.shared.*;
import io.github.cskwork.lunepick.wishlist.internal.application.WishlistService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/wishlist")
public class WishlistController {
    public record AddRequest(
            @NotNull(message = "상품을 입력해주세요.") @Positive(message = "상품 번호는 양수여야 합니다.")
                    Long productId) {}

    private final WishlistService wishlist;

    public WishlistController(WishlistService wishlist) {
        this.wishlist = wishlist;
    }

    @GetMapping
    @Operation(summary = "내 찜 목록", description = "최신 등록 순서의 찜 목록을 페이지별로 조회합니다.")
    ApiResponse<List<WishlistService.Item>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        var result =
                wishlist.list(Long.parseLong(jwt.getSubject()), PageQuery.from(page, size, 20));
        return ApiResponse.page(result.items(), result.meta());
    }

    @PostMapping
    @Operation(summary = "찜 추가", description = "동일 상품을 중복 등록하지 않습니다. 재요청도 성공합니다.")
    ApiResponse<Void> add(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddRequest request) {
        wishlist.add(Long.parseLong(jwt.getSubject()), request.productId());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{productId}")
    @Operation(summary = "찜 삭제", description = "내 찜 목록에서 상품을 삭제합니다. 이미 없어도 성공합니다.")
    ApiResponse<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long productId) {
        wishlist.remove(Long.parseLong(jwt.getSubject()), productId);
        return ApiResponse.ok(null);
    }
}
