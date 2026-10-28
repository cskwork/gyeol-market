package io.github.cskwork.lunepick.catalog.internal.persistence;

import static io.github.cskwork.lunepick.catalog.internal.application.CatalogViews.*;

import io.github.cskwork.lunepick.catalog.internal.application.CatalogStore;
import io.github.cskwork.lunepick.catalog.internal.domain.CatalogProduct;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcCatalogStore implements CatalogStore {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private static final String VISIBLE = "p.status <> 'HIDDEN'";
    private static final String PRODUCTS =
            """
            SELECT p.id, p.brand_id, b.name AS brand_name, b.logo_url AS brand_logo_url,
                   p.name, p.description, p.base_price, p.sale_price, i.url AS thumbnail_url,
                   COALESCE((SELECT json_agg(pc.category_id ORDER BY pc.category_id)::text
                       FROM product_category pc WHERE pc.product_id = p.id), '[]') AS category_ids,
                   p.created_order, COALESCE(r.rating, 0) AS rating,
                   COALESCE(r.review_count, 0) AS review_count,
                   (p.status = 'ON_SALE' AND EXISTS (SELECT 1 FROM product_option o
                       WHERE o.product_id = p.id AND o.status = 'ON_SALE'
                         AND (o.available_quantity IS NULL OR o.available_quantity > 0))) AS in_stock
              FROM product p JOIN brand b ON b.id = p.brand_id
              LEFT JOIN product_image i ON i.product_id = p.id AND i.is_thumbnail = true
              LEFT JOIN LATERAL (SELECT AVG(rating) AS rating, COUNT(*) AS review_count
                                   FROM product_review WHERE product_id = p.id) r ON true
            """;

    public JdbcCatalogStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<CatalogProduct> products() {
        return products(jdbc.sql(PRODUCTS + " WHERE " + VISIBLE + " ORDER BY p.id"));
    }

    @Override
    public List<CatalogProduct> products(long afterProductId, int size) {
        return products(
                jdbc.sql(
                                PRODUCTS
                                        + " WHERE "
                                        + VISIBLE
                                        + " AND p.id > :after ORDER BY p.id LIMIT :size")
                        .param("after", afterProductId)
                        .param("size", size));
    }

    @Override
    public List<CatalogProduct> products(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return products(
                jdbc.sql(PRODUCTS + " WHERE " + VISIBLE + " AND p.id IN (:ids) ORDER BY p.id")
                        .param("ids", ids));
    }

    @Override
    public Optional<CatalogProduct> product(long id) {
        return products(
                        jdbc.sql(PRODUCTS + " WHERE " + VISIBLE + " AND p.id = :id")
                                .param("id", id))
                .stream()
                .findFirst();
    }

    @Override
    public ProductPage productPage(
            Long categoryId, Long brandId, String sort, long offset, int size) {
        String where =
                " WHERE "
                        + VISIBLE
                        + (categoryId == null
                                ? ""
                                : " AND EXISTS (SELECT 1 FROM product_category f WHERE f.product_id"
                                        + " = p.id AND f.category_id = :categoryId)")
                        + (brandId == null ? "" : " AND p.brand_id = :brandId");
        long total =
                filters(jdbc.sql("SELECT COUNT(*) FROM product p" + where), categoryId, brandId)
                        .query(Long.class)
                        .single();
        if (offset >= total) return new ProductPage(List.of(), total);
        var ids =
                filters(
                                jdbc.sql(
                                        "SELECT p.id FROM product p"
                                                + where
                                                + " ORDER BY "
                                                + orderBy(sort)
                                                + " LIMIT :size OFFSET :offset"),
                                categoryId,
                                brandId)
                        .param("size", size)
                        .param("offset", offset)
                        .query(Long.class)
                        .list();
        Map<Long, CatalogProduct> byId =
                products(ids).stream()
                        .collect(Collectors.toMap(CatalogProduct::id, Function.identity()));
        return new ProductPage(ids.stream().map(byId::get).toList(), total);
    }

    private JdbcClient.StatementSpec filters(
            JdbcClient.StatementSpec query, Long categoryId, Long brandId) {
        if (categoryId != null) query.param("categoryId", categoryId);
        if (brandId != null) query.param("brandId", brandId);
        return query;
    }

    private String orderBy(String sort) {
        if (sort == null) return "p.created_order DESC, p.id ASC";
        return switch (sort) {
            case "PRICE_ASC" -> "p.sale_price ASC, p.id ASC";
            case "PRICE_DESC" -> "p.sale_price DESC, p.id ASC";
            case "LATEST" -> "p.created_order DESC, p.id ASC";
            case "POPULAR" ->
                    "COALESCE(round((p.base_price - p.sale_price) / NULLIF(p.base_price, 0) * 100,"
                            + " 1), 0) DESC, p.id ASC";
            default -> "p.id ASC";
        };
    }

    private List<CatalogProduct> products(JdbcClient.StatementSpec query) {
        return query.query(ProductRow.class).list().stream()
                .map(
                        row ->
                                new CatalogProduct(
                                        row.id(),
                                        row.brandId(),
                                        row.brandName(),
                                        row.brandLogoUrl(),
                                        row.name(),
                                        row.description(),
                                        row.basePrice(),
                                        row.salePrice(),
                                        row.thumbnailUrl(),
                                        List.of(mapper.readValue(row.categoryIds(), Long[].class)),
                                        row.createdOrder(),
                                        row.rating(),
                                        row.reviewCount(),
                                        row.inStock()))
                .toList();
    }

    private record ProductRow(
            long id,
            long brandId,
            String brandName,
            String brandLogoUrl,
            String name,
            String description,
            BigDecimal basePrice,
            BigDecimal salePrice,
            String thumbnailUrl,
            String categoryIds,
            long createdOrder,
            BigDecimal rating,
            long reviewCount,
            boolean inStock) {}

    @Override
    public List<Category> categories() {
        return jdbc.sql(
                        "SELECT id, parent_id, name, slug, sort_order FROM category ORDER BY"
                                + " sort_order, id")
                .query(Category.class)
                .list();
    }

    @Override
    public List<Option> options(long productId) {
        return jdbc.sql(
                        """
                        SELECT id AS option_id, name AS option_name, price AS option_price, status, available_quantity
                          FROM product_option WHERE product_id = :id ORDER BY sort_order, id
                        """)
                .param("id", productId)
                .query(Option.class)
                .list();
    }

    @Override
    public List<Image> images(long productId) {
        return jdbc.sql(
                        """
                        SELECT id AS image_id, url, sort_order, is_thumbnail FROM product_image
                         WHERE product_id = :id ORDER BY sort_order, id
                        """)
                .param("id", productId)
                .query(Image.class)
                .list();
    }

    @Override
    public List<Review> reviews(long productId, int offset, int size) {
        return jdbc
                .sql(
                        """
                        SELECT id, product_id, rating, title, content AS body, image_urls::text, created_at
                          FROM product_review WHERE product_id = :id ORDER BY created_at DESC, id DESC
                         LIMIT :size OFFSET :offset
                        """)
                .param("id", productId)
                .param("size", size)
                .param("offset", offset)
                .query(ReviewRow.class)
                .list()
                .stream()
                .map(
                        row ->
                                new Review(
                                        row.id(),
                                        row.productId(),
                                        row.rating(),
                                        row.title(),
                                        row.body(),
                                        row.imageUrls() == null
                                                ? null
                                                : List.of(
                                                        mapper.readValue(
                                                                row.imageUrls(), String[].class)),
                                        row.createdAt()))
                .toList();
    }

    private record ReviewRow(
            long id,
            long productId,
            BigDecimal rating,
            String title,
            String body,
            String imageUrls,
            OffsetDateTime createdAt) {}

    @Override
    public long reviewCount(long productId) {
        return jdbc.sql("SELECT COUNT(*) FROM product_review WHERE product_id = :id")
                .param("id", productId)
                .query(Long.class)
                .single();
    }
}
