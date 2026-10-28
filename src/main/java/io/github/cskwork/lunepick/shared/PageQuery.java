package io.github.cskwork.lunepick.shared;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Zero-based pagination with a bounded integer page size. */
public record PageQuery(@Min(0) int page, @Min(1) @Max(100) int size) {
    public PageQuery {
        if (page < 0 || size < 1 || size > 100) throw invalid();
    }

    public static PageQuery from(String page, String size, int defaultSize) {
        return new PageQuery(integer(page, 0), integer(size, defaultSize));
    }

    public long offset() {
        return (long) page * size;
    }

    public int limit() {
        return size;
    }

    private static int integer(String value, int fallback) {
        if (value == null) return fallback;
        if (!value.matches("[0-9]+")) throw invalid();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(400, "INVALID_PARAMETER", "페이지와 크기는 허용 범위의 정수여야 합니다.");
    }
}
