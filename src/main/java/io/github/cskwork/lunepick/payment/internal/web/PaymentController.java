package io.github.cskwork.lunepick.payment.internal.web;

import io.github.cskwork.lunepick.payment.internal.application.PaymentService;
import io.github.cskwork.lunepick.payment.internal.domain.WebhookSignature;
import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService payments;
    private final WebhookSignature signatures;
    private final ObjectMapper mapper;
    private final Validator validator;

    public PaymentController(
            PaymentService payments,
            WebhookSignature signatures,
            ObjectMapper mapper,
            Validator validator) {
        this.payments = payments;
        this.signatures = signatures;
        this.mapper = mapper;
        this.validator = validator;
    }

    @PostMapping("/confirm")
    @Operation(
            summary = "주문 결제 승인",
            description =
                    "주문 소유자와 결제 금액을 확인하고 같은 결제 키의 결과를 재사용합니다. 설정된 PaymentGateway 어댑터에서 승인한 뒤"
                            + " 정산합니다.")
    public ApiResponse<PaymentService.Confirmation> confirm(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConfirmRequest request) {
        return ApiResponse.ok(
                payments.confirm(
                        Long.parseLong(jwt.getSubject()),
                        request.orderNo(),
                        request.paymentKey(),
                        request.amount()));
    }

    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "PG 결제 상태 웹훅",
            description =
                    "X-PG-Timestamp(Unix 초)와 원문을 timestamp.body로 서명한 HMAC-SHA256을 검사합니다. 허용 시간 차이는"
                            + " 5분입니다. 같은 eventId와 원문은 processed=false로 응답합니다. 금액·키 불일치와 역순 이벤트는"
                            + " NEEDS_REVIEW로 보존하고 200으로 응답합니다.")
    public ApiResponse<PaymentService.WebhookAck> webhook(
            @RequestBody byte[] body,
            @Parameter(
                            description =
                                    "Raw request bytes signed with HMAC-SHA256, encoded as 64"
                                            + " hexadecimal characters")
                    @RequestHeader(name = "X-PG-Signature", required = false)
                    String signature,
            @RequestHeader(name = "X-PG-Timestamp", required = false) String timestamp) {
        if (body.length > 16384)
            throw new ApiException(413, "PAYLOAD_TOO_LARGE", "웹훅 요청은 16384바이트 이하여야 합니다.");
        signatures.verify(body, timestamp, signature);
        WebhookRequest request;
        try {
            request = mapper.readValue(body, WebhookRequest.class);
        } catch (JacksonException malformed) {
            throw validation();
        }
        if (request == null || !validator.validate(request).isEmpty()) throw validation();
        return ApiResponse.ok(
                payments.webhook(
                        new PaymentService.WebhookEvent(
                                request.eventId(),
                                request.orderNo(),
                                request.paymentKey(),
                                request.amount(),
                                request.status()),
                        signatures.payloadHash(body)));
    }

    private ApiException validation() {
        return new ApiException(400, "VALIDATION_FAILED", "입력값을 확인해주세요.");
    }

    public record ConfirmRequest(
            @NotBlank @Size(max = 100) String orderNo,
            @NotBlank @Size(max = 200) String paymentKey,
            @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal amount) {}

    public record WebhookRequest(
            @NotBlank @Size(max = 200) String eventId,
            @NotBlank @Size(max = 100) String orderNo,
            @NotBlank @Size(max = 200) String paymentKey,
            @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @NotNull @Pattern(regexp = "CONFIRMED|FAILED|CANCELLED") String status) {}
}
