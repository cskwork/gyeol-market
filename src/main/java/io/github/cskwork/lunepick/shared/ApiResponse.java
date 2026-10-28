package io.github.cskwork.lunepick.shared;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ApiErrorBody error, PageMeta meta) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> page(T data, PageMeta meta) {
        return new ApiResponse<>(true, data, null, meta);
    }

    public static ApiResponse<Void> failure(ApiErrorBody error) {
        return new ApiResponse<>(false, null, error, null);
    }
}
