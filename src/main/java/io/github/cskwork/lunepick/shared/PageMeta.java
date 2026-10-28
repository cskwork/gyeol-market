package io.github.cskwork.lunepick.shared;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record PageMeta(@Min(0) int page, @Min(1) @Max(100) int size, @Min(0) long total) {
    public PageMeta {
        new PageQuery(page, size);
        if (total < 0) throw new IllegalArgumentException("total must not be negative");
    }
}
