package io.github.cskwork.lunepick.catalog;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The catalog's immutable read contract for other application modules. */
public interface CatalogApi {
    List<SearchProduct> searchSnapshot();

    Optional<SearchProduct> searchProduct(long productId);

    List<SearchProduct> searchSnapshot(long afterProductId, int size);

    List<ProductListItem> summaries(Collection<Long> productIds);

    record SearchProduct(
            long productId,
            long brandId,
            String brandName,
            String productName,
            String description,
            BigDecimal salePrice,
            BigDecimal originalPrice,
            BigDecimal discountRate,
            String thumbnailUrl,
            List<Long> categoryIds,
            long createdOrder,
            BigDecimal rating,
            long reviewCount,
            boolean inStock) {
        public SearchProduct {
            categoryIds = List.copyOf(categoryIds);
        }

        public SearchProduct(
                long productId,
                long brandId,
                String brandName,
                String productName,
                String description,
                BigDecimal salePrice,
                BigDecimal originalPrice,
                BigDecimal discountRate,
                String thumbnailUrl,
                List<Long> categoryIds,
                long createdOrder,
                BigDecimal rating,
                long reviewCount) {
            this(
                    productId,
                    brandId,
                    brandName,
                    productName,
                    description,
                    salePrice,
                    originalPrice,
                    discountRate,
                    thumbnailUrl,
                    categoryIds,
                    createdOrder,
                    rating,
                    reviewCount,
                    true);
        }

        public ProductListItem toListItem() {
            return new ProductListItem(
                    productId,
                    brandName,
                    productName,
                    salePrice,
                    originalPrice,
                    discountRate,
                    thumbnailUrl,
                    rating,
                    reviewCount,
                    inStock);
        }
    }

    record ProductListItem(
            long productId,
            String brandName,
            String productName,
            BigDecimal salePrice,
            BigDecimal originalPrice,
            BigDecimal discountRate,
            String thumbnailUrl,
            BigDecimal rating,
            long reviewCount,
            boolean inStock) {
        public ProductListItem(
                long productId,
                String brandName,
                String productName,
                BigDecimal salePrice,
                BigDecimal originalPrice,
                BigDecimal discountRate,
                String thumbnailUrl,
                BigDecimal rating,
                long reviewCount) {
            this(
                    productId,
                    brandName,
                    productName,
                    salePrice,
                    originalPrice,
                    discountRate,
                    thumbnailUrl,
                    rating,
                    reviewCount,
                    true);
        }
    }
}
