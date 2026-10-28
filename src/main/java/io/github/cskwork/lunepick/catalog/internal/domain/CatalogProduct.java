package io.github.cskwork.lunepick.catalog.internal.domain;

import io.github.cskwork.lunepick.catalog.CatalogApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

public record CatalogProduct(
        long id,
        long brandId,
        String brandName,
        String brandLogoUrl,
        String name,
        String description,
        BigDecimal basePrice,
        BigDecimal salePrice,
        String thumbnailUrl,
        List<Long> categoryIds,
        long createdOrder,
        BigDecimal rating,
        long reviewCount,
        boolean inStock) {
    public CatalogProduct {
        categoryIds = List.copyOf(categoryIds);
    }

    public CatalogProduct(
            long id,
            long brandId,
            String brandName,
            String brandLogoUrl,
            String name,
            String description,
            BigDecimal basePrice,
            BigDecimal salePrice,
            String thumbnailUrl,
            List<Long> categoryIds,
            long createdOrder,
            BigDecimal rating,
            long reviewCount) {
        this(
                id,
                brandId,
                brandName,
                brandLogoUrl,
                name,
                description,
                basePrice,
                salePrice,
                thumbnailUrl,
                categoryIds,
                createdOrder,
                rating,
                reviewCount,
                true);
    }

    public BigDecimal discountRate() {
        if (basePrice.signum() == 0) return BigDecimal.ZERO;
        return basePrice
                .subtract(salePrice)
                .multiply(BigDecimal.valueOf(100))
                .divide(basePrice, 1, RoundingMode.HALF_UP);
    }

    public CatalogApi.SearchProduct searchProduct() {
        return new CatalogApi.SearchProduct(
                id,
                brandId,
                brandName,
                name,
                description,
                salePrice,
                basePrice,
                discountRate(),
                thumbnailUrl,
                categoryIds,
                createdOrder,
                rating,
                reviewCount,
                inStock);
    }

    public CatalogApi.ProductListItem listItem() {
        return new CatalogApi.ProductListItem(
                id,
                brandName,
                name,
                salePrice,
                basePrice,
                discountRate(),
                thumbnailUrl,
                rating,
                reviewCount,
                inStock);
    }

    public static Comparator<CatalogProduct> sort(String sort) {
        Comparator<CatalogProduct> byId = Comparator.comparingLong(CatalogProduct::id);
        if (sort == null)
            return Comparator.comparingLong(CatalogProduct::createdOrder)
                    .reversed()
                    .thenComparing(byId);
        return switch (sort) {
            case "PRICE_ASC" -> Comparator.comparing(CatalogProduct::salePrice).thenComparing(byId);
            case "PRICE_DESC" ->
                    Comparator.comparing(CatalogProduct::salePrice).reversed().thenComparing(byId);
            case "POPULAR" ->
                    Comparator.comparing(CatalogProduct::discountRate)
                            .reversed()
                            .thenComparing(byId);
            case "LATEST" ->
                    Comparator.comparingLong(CatalogProduct::createdOrder)
                            .reversed()
                            .thenComparing(byId);
            default -> byId;
        };
    }
}
