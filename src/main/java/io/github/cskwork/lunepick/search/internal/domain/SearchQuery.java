package io.github.cskwork.lunepick.search.internal.domain;

import io.github.cskwork.lunepick.shared.ApiException;
import java.util.Locale;

public record SearchQuery(String keyword, Long categoryId, String orderBy) {
    public static SearchQuery from(String keyword, String categoryId, String sort) {
        String orderBy =
                sort == null
                        ? "created_order DESC, product_id ASC"
                        : switch (sort) {
                            case "LATEST" -> "created_order DESC, product_id ASC";
                            case "POPULAR" -> "discount_rate DESC, product_id ASC";
                            case "PRICE_ASC" -> "sale_price ASC, product_id ASC";
                            case "PRICE_DESC" -> "sale_price DESC, product_id ASC";
                            case "RELEVANCE" -> "relevance DESC, product_id ASC";
                            // Rating and unknown sorts retain stable storefront ordering.
                            default -> "product_id ASC";
                        };
        return new SearchQuery(
                keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT),
                positiveId(categoryId, "Category ID"),
                orderBy);
    }

    public static Long positiveId(String value, String parameter) {
        if (value == null) return null;
        try {
            if (value.matches("[1-9][0-9]*")) return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            // Overflow has the same client-facing result as other invalid identifiers.
        }
        throw new ApiException(400, "INVALID_PARAMETER", parameter + " must be a positive integer");
    }

    public static String containsPattern(String value) {
        // JavaScript includes() treats SQL wildcard characters literally.
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
