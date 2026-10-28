package io.github.cskwork.lunepick.shared;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorBody(
        String code, String message, String path, String traceId, List<FieldError> fieldErrors) {
    public static ApiErrorBody of(String code, String message, String path, String traceId) {
        return new ApiErrorBody(code, message, path, traceId, null);
    }

    public record FieldError(String field, String message, Object rejectedValue) {}
}
