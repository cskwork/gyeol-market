package io.github.cskwork.lunepick.cart.internal.application;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class CartViews {
    private CartViews() {}

    public record AddRequest(
            @NotNull(message = "상품 옵션을 입력해주세요.") @Positive(message = "상품 옵션은 양수여야 합니다.")
                    Long productOptionId,
            @NotNull(message = "수량을 입력해주세요.") BigDecimal quantity) {}

    public record UpdateRequest(@NotNull(message = "수량을 입력해주세요.") BigDecimal quantity) {}

    public record MergeRequest(
            @NotBlank(message = "세션 ID를 입력해주세요.")
                    @Size(max = 512, message = "세션 ID는 512자 이내로 입력해주세요.")
                    String sessionId) {}

    public record Added(long cartItemId, int quantity) {}

    public record Merged(int mergedItemCount) {}

    public record Item(
            long cartItemId,
            long productOptionId,
            String optionName,
            String productName,
            BigDecimal salePrice,
            boolean onSale,
            Long availableQuantity,
            int quantity,
            BigDecimal lineSubtotal,
            String productStatus,
            long productId,
            String thumbnailUrl) {}

    public record Cart(List<Item> items, int totalItemCount, BigDecimal totalAmount) {}
}
