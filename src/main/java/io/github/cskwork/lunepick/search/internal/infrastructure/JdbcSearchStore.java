package io.github.cskwork.lunepick.search.internal.infrastructure;

import io.github.cskwork.lunepick.catalog.CatalogApi.ProductListItem;
import io.github.cskwork.lunepick.catalog.CatalogApi.SearchProduct;
import io.github.cskwork.lunepick.search.SearchApi.SearchResult;
import io.github.cskwork.lunepick.search.internal.application.SearchStore;
import io.github.cskwork.lunepick.search.internal.domain.SearchQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSearchStore implements SearchStore {
    private static final String CONTAINS =
            """
      (lower(product_name) LIKE :pattern ESCAPE '!'
       OR lower(description) LIKE :pattern ESCAPE '!'
       OR lower(brand_name) LIKE :pattern ESCAPE '!')
      """;
    private final JdbcClient jdbc;

    JdbcSearchStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public SearchResult search(SearchQuery query, int size, long offset) {
        SqlSelection selection = selection(query);
        long total =
                jdbc.sql(selection.sql() + " SELECT count(*) FROM matches")
                        .params(selection.arguments())
                        .query(Long.class)
                        .single();
        List<ProductListItem> products =
                jdbc.sql(
                                selection.sql()
                                        + " SELECT * FROM matches ORDER BY "
                                        + query.orderBy()
                                        + " LIMIT :size OFFSET :offset")
                        .params(selection.arguments())
                        .param("size", size)
                        .param("offset", offset)
                        .query(
                                (rs, row) ->
                                        new ProductListItem(
                                                rs.getLong("product_id"),
                                                rs.getString("brand_name"),
                                                rs.getString("product_name"),
                                                rs.getBigDecimal("sale_price"),
                                                rs.getBigDecimal("original_price"),
                                                rs.getBigDecimal("discount_rate"),
                                                rs.getString("thumbnail_url"),
                                                rs.getBigDecimal("rating"),
                                                rs.getLong("review_count"),
                                                rs.getBoolean("in_stock")))
                        .list();
        return new SearchResult(products, total);
    }

    private SqlSelection selection(SearchQuery query) {
        Map<String, Object> args = new HashMap<>();
        String source;
        if (query.keyword().isEmpty()) {
            source = "SELECT *, 0::real AS relevance FROM search_documents";
        } else {
            String pattern = SearchQuery.containsPattern(query.keyword());
            // FTS handles independent terms across fields. Trigram-indexed literal matching
            // also keeps Korean infix and punctuation queries useful without a language plugin.
            // Name similarity gives Korean compounds relevance even when no whole FTS token
            // matches.
            // Normalized FTS rank retains multi-term evidence without overwhelming the name score.
            source =
                    "SELECT *, ts_rank_cd(search_vector, plainto_tsquery('simple', :keyword), 32)"
                            + " + similarity(lower(product_name), :keyword) AS relevance"
                            + " FROM search_documents WHERE search_vector @@"
                            + " plainto_tsquery('simple', :keyword) OR "
                            + CONTAINS;
            args.put("keyword", query.keyword());
            args.put("pattern", pattern);
        }
        String category = "";
        if (query.categoryId() != null) {
            category = " WHERE :category = ANY(category_ids)";
            args.put("category", query.categoryId());
        }
        return new SqlSelection(
                "WITH candidates AS ("
                        + source
                        + "), matches AS (SELECT * FROM candidates"
                        + category
                        + ")",
                args);
    }

    public List<String> autocomplete(String prefix, int size) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        String pattern = SearchQuery.containsPattern(normalized);
        String prefixPattern = pattern.substring(1);
        return jdbc.sql(
                        """
            SELECT product_name
            FROM search_documents
            WHERE lower(product_name) LIKE :pattern ESCAPE '!'
               OR (length(:normalized) >= 3 AND lower(product_name) % :normalized)
            GROUP BY product_name
            ORDER BY CASE WHEN lower(product_name) LIKE :prefixPattern ESCAPE '!' THEN 0 ELSE 1 END,
                similarity(lower(product_name), :normalized) DESC, min(product_id)
            LIMIT :size
            """)
                .param("pattern", pattern)
                .param("normalized", normalized)
                .param("prefixPattern", prefixPattern)
                .param("size", size)
                .query(String.class)
                .list();
    }

    public void lockForInitialization() {
        // Serialize empty-table initialization with writers; an existing projection is untouched.
        jdbc.sql("LOCK TABLE search_documents IN SHARE ROW EXCLUSIVE MODE").update();
    }

    public boolean isEmpty() {
        return !jdbc.sql("SELECT EXISTS(SELECT 1 FROM search_documents)")
                .query(Boolean.class)
                .single();
    }

    public void remove(long productId) {
        jdbc.sql("DELETE FROM search_documents WHERE product_id = :productId")
                .param("productId", productId)
                .update();
    }

    public void upsert(SearchProduct product) {
        jdbc.sql(
                        """
            INSERT INTO search_documents
                (product_id, brand_id, brand_name, product_name, description, sale_price,
                 original_price, discount_rate, thumbnail_url, category_ids, created_order, rating, review_count, in_stock)
            VALUES (:productId, :brandId, :brandName, :productName, :description, :salePrice,
                    :originalPrice, :discountRate, :thumbnailUrl, :categoryIds, :createdOrder, :rating,
                    :reviewCount, :inStock)
            ON CONFLICT (product_id) DO UPDATE SET
                brand_id = excluded.brand_id, brand_name = excluded.brand_name,
                product_name = excluded.product_name, description = excluded.description,
                sale_price = excluded.sale_price, original_price = excluded.original_price,
                discount_rate = excluded.discount_rate, thumbnail_url = excluded.thumbnail_url,
                category_ids = excluded.category_ids, created_order = excluded.created_order,
                rating = excluded.rating, review_count = excluded.review_count,
                in_stock = excluded.in_stock
            """)
                .param("productId", product.productId())
                .param("brandId", product.brandId())
                .param("brandName", product.brandName())
                .param("productName", product.productName())
                .param("description", product.description() == null ? "" : product.description())
                .param("salePrice", product.salePrice())
                .param("originalPrice", product.originalPrice())
                .param("discountRate", product.discountRate())
                .param("thumbnailUrl", product.thumbnailUrl())
                .param("categoryIds", new SqlArrayValue("bigint", product.categoryIds().toArray()))
                .param("createdOrder", product.createdOrder())
                .param("rating", product.rating())
                .param("reviewCount", product.reviewCount())
                .param("inStock", product.inStock())
                .update();
    }

    private record SqlSelection(String sql, Map<String, Object> arguments) {}
}
