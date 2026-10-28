package io.github.cskwork.lunepick.shared.internal.web;

import io.github.cskwork.lunepick.shared.ApiErrorBody;
import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> domain(ApiException e, HttpServletRequest request) {
        return failure(e.status(), e.code(), e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        var fields =
                e.getBindingResult().getFieldErrors().stream()
                        .map(
                                f ->
                                        new ApiErrorBody.FieldError(
                                                f.getField(),
                                                f.getDefaultMessage(),
                                                sensitive(f.getField())
                                                        ? null
                                                        : f.getRejectedValue()))
                        .toList();
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.failure(
                                new ApiErrorBody(
                                        "VALIDATION_FAILED",
                                        "입력값을 확인해주세요.",
                                        request.getRequestURI(),
                                        traceId(request),
                                        fields)));
    }

    private boolean sensitive(String field) {
        return field.toLowerCase().contains("password") || field.toLowerCase().contains("token");
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<Void>> invalid(Exception e, HttpServletRequest request) {
        return failure(400, "INVALID_PARAMETER", "잘못된 요청입니다.", request);
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ApiResponse<Void>> constraints(Exception e, HttpServletRequest request) {
        return failure(400, "VALIDATION_FAILED", "입력값을 확인해주세요.", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> missing(Exception e, HttpServletRequest request) {
        return failure(404, "NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> method(Exception e, HttpServletRequest request) {
        return failure(405, "METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다.", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> mediaType(Exception e, HttpServletRequest request) {
        return failure(415, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 Content-Type입니다.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e, HttpServletRequest request) {
        // Correlate the complete stack trace without logging request bodies or headers.
        log.error("Unhandled request failure: requestId={}", traceId(request), e);
        return failure(500, "INTERNAL_ERROR", "요청을 처리하지 못했습니다.", request);
    }

    private ResponseEntity<ApiResponse<Void>> failure(
            int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.failure(
                                ApiErrorBody.of(
                                        code, message, request.getRequestURI(), traceId(request))));
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
    }
}
