package io.github.cskwork.lunepick.cart.internal.application;

import io.github.cskwork.lunepick.cart.CartApi;
import io.github.cskwork.lunepick.cart.internal.domain.Quantity;
import io.github.cskwork.lunepick.catalog.CatalogApi;
import io.github.cskwork.lunepick.catalog.CatalogStockApi;
import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CartService implements CartApi {
    private final CartStore store;
    private final CatalogStockApi catalog;
    private final CatalogApi products;

    public CartService(CartStore store, CatalogStockApi catalog, CatalogApi products) {
        this.store = store;
        this.catalog = catalog;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public CartViews.Cart view(long memberId) {
        var lines = store.lines(memberId);
        var options =
                catalog.options(lines.stream().map(CartStore.Line::optionId).toList()).stream()
                        .collect(
                                Collectors.toMap(
                                        CatalogStockApi.OptionSnapshot::optionId,
                                        Function.identity()));
        var summaries =
                products
                        .summaries(
                                options.values().stream()
                                        .map(CatalogStockApi.OptionSnapshot::productId)
                                        .distinct()
                                        .toList())
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        CatalogApi.ProductListItem::productId,
                                        Function.identity()));
        var items =
                lines.stream()
                        .filter(line -> options.containsKey(line.optionId()))
                        .map(
                                line -> {
                                    var option = options.get(line.optionId());
                                    var product = summaries.get(option.productId());
                                    return new CartViews.Item(
                                            line.id(),
                                            line.optionId(),
                                            option.optionName(),
                                            option.productName(),
                                            option.salePrice(),
                                            "ON_SALE".equals(option.status()),
                                            option.availableQuantity(),
                                            line.quantity(),
                                            option.salePrice()
                                                    .multiply(BigDecimal.valueOf(line.quantity())),
                                            option.status(),
                                            option.productId(),
                                            product == null ? null : product.thumbnailUrl());
                                })
                        .toList();
        return new CartViews.Cart(
                items,
                items.stream().mapToInt(CartViews.Item::quantity).sum(),
                items.stream()
                        .map(CartViews.Item::lineSubtotal)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public CartViews.Added add(long memberId, long optionId, BigDecimal quantity) {
        requireOption(optionId);
        var saved = store.add(memberId, optionId, Quantity.of(quantity));
        return new CartViews.Added(saved.id(), saved.quantity());
    }

    public void update(long memberId, long itemId, BigDecimal quantity) {
        if (!store.update(memberId, itemId, Quantity.of(quantity))) throw missing();
    }

    public void remove(long memberId, long itemId) {
        if (!store.delete(memberId, itemId)) throw missing();
    }

    public CartViews.Merged merge(long memberId, String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 512)
            throw new ApiException(400, "VALIDATION_FAILED", "세션 ID를 확인해주세요.");
        // No anonymous cart is stored by this API; keep the storefront's empty merge response.
        return new CartViews.Merged(0);
    }

    @Transactional(readOnly = true)
    public List<Long> selectedItemIds(long memberId, List<Long> optionIds) {
        return store.lines(memberId).stream()
                .filter(line -> optionIds.contains(line.optionId()))
                .map(CartStore.Line::id)
                .toList();
    }

    public void clearPurchased(long memberId, List<Long> cartItemIds) {
        for (long id : cartItemIds) store.delete(memberId, id);
    }

    private void requireOption(long id) {
        if (catalog.option(id).isEmpty())
            throw new ApiException(404, "PRODUCT_OPTION_NOT_FOUND", "상품 옵션을 찾을 수 없습니다.");
    }

    private static ApiException missing() {
        return new ApiException(404, "CART_ITEM_NOT_FOUND", "장바구니 상품을 찾을 수 없습니다.");
    }
}
