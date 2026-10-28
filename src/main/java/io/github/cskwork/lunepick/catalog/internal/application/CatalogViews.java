package io.github.cskwork.lunepick.catalog.internal.application;

import io.github.cskwork.lunepick.catalog.CatalogApi.ProductListItem;
import io.github.cskwork.lunepick.catalog.internal.domain.CatalogProduct;
import io.github.cskwork.lunepick.shared.PageMeta;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class CatalogViews {
    private CatalogViews() {}

    public record Page<T>(List<T> items, PageMeta meta) {}

    public record Category(long id, Long parentId, String name, String slug, int sortOrder) {}

    public record CategoryNode(long id, String name, String slug, List<CategoryNode> children) {}

    public record CategoryTree(List<CategoryNode> categories) {}

    public record CategoryPath(long categoryId, String categoryName, String categorySlug) {}

    public record Option(
            long optionId,
            String optionName,
            BigDecimal optionPrice,
            String status,
            Long availableQuantity) {}

    public record Image(long imageId, String url, int sortOrder, boolean isThumbnail) {}

    public record Review(
            long id,
            long productId,
            BigDecimal rating,
            String title,
            String body,
            List<String> imageUrls,
            OffsetDateTime createdAt) {}

    public record ProductDetail(
            long productId,
            String brandName,
            String productName,
            BigDecimal salePrice,
            BigDecimal originalPrice,
            BigDecimal discountRate,
            String thumbnailUrl,
            BigDecimal rating,
            long reviewCount,
            String brandLogoUrl,
            String description,
            List<Option> options,
            List<Image> images,
            List<CategoryPath> categories) {
        public static ProductDetail from(
                CatalogProduct p,
                List<Option> options,
                List<Image> images,
                List<CategoryPath> categories) {
            ProductListItem item = p.listItem();
            return new ProductDetail(
                    item.productId(),
                    item.brandName(),
                    item.productName(),
                    item.salePrice(),
                    item.originalPrice(),
                    item.discountRate(),
                    item.thumbnailUrl(),
                    item.rating(),
                    item.reviewCount(),
                    p.brandLogoUrl(),
                    p.description(),
                    options,
                    images,
                    categories);
        }
    }
}
