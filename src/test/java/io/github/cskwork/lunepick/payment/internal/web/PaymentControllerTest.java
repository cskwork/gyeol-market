package io.github.cskwork.lunepick.payment.internal.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cskwork.lunepick.payment.internal.application.PaymentService;
import io.github.cskwork.lunepick.payment.internal.config.PaymentProperties;
import io.github.cskwork.lunepick.payment.internal.config.PaymentWebPolicy;
import io.github.cskwork.lunepick.payment.internal.domain.WebhookSignature;
import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.internal.web.ApiExceptionHandler;
import io.github.cskwork.lunepick.shared.internal.web.SecurityConfiguration;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
@Import({
    SecurityConfiguration.class,
    PaymentWebPolicy.class,
    ApiExceptionHandler.class,
    PaymentControllerTest.SignatureConfiguration.class
})
class PaymentControllerTest {
    static final String SECRET = "test-payment-webhook-secret-at-least-32-bytes";
    private final String timestamp = Long.toString(Instant.now().getEpochSecond());
    @Autowired MockMvc mvc;
    @MockitoBean PaymentService payments;
    @MockitoBean JwtDecoder decoder;

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentProperties.class)
    static class SignatureConfiguration {
        @Bean
        WebhookSignature webhookSignature() {
            return new WebhookSignature(SECRET);
        }
    }

    @Test
    void confirmationUsesAuthenticatedMemberAndStringPaymentKey() throws Exception {
        when(payments.confirm(7, "O-1", "100001", new BigDecimal("10")))
                .thenReturn(new PaymentService.Confirmation(1, "O-1", "PAID", "100001"));
        mvc.perform(
                        post("/api/payments/confirm")
                                .with(jwt().jwt(j -> j.subject("7")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"orderNo\":\"O-1\",\"paymentKey\":\"100001\",\"amount\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(1))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.paymentKey").value("100001"));
    }

    @Test
    void confirmationRequiresAuthenticationAndValidPayload() throws Exception {
        mvc.perform(
                        post("/api/payments/confirm")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/payments/confirm")
                                .with(jwt().jwt(j -> j.subject("7")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"orderNo\":\"\",\"paymentKey\":\"\",\"amount\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(payments);
    }

    @Test
    void confirmationPreservesAmountMismatchResponse() throws Exception {
        when(payments.confirm(anyLong(), anyString(), anyString(), any()))
                .thenThrow(
                        new ApiException(
                                422, "PAYMENT_AMOUNT_MISMATCH", "결제 금액이 주문 금액과 일치하지 않습니다."));
        mvc.perform(
                        post("/api/payments/confirm")
                                .with(jwt().jwt(j -> j.subject("7")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"orderNo\":\"O-1\",\"paymentKey\":\"key\",\"amount\":10}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.error.message").value("결제 금액이 주문 금액과 일치하지 않습니다."));
    }

    @Test
    void signedWebhookIsPublicAndReturnsDeduplicationAcknowledgement() throws Exception {
        String body = body("CONFIRMED");
        when(payments.webhook(any(), anyString()))
                .thenReturn(new PaymentService.WebhookAck("evt", false));
        mvc.perform(
                        post("/api/payments/webhook")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                                .header("X-PG-Timestamp", timestamp)
                                .header("X-PG-Signature", sign(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventId").value("evt"))
                .andExpect(jsonPath("$.data.processed").value(false));
        verify(payments)
                .webhook(
                        eq(
                                new PaymentService.WebhookEvent(
                                        "evt", "O-1", "key", BigDecimal.TEN, "CONFIRMED")),
                        matches("[a-f0-9]{64}"));
    }

    @Test
    void signatureIsCheckedBeforeJsonOrStatusProcessing() throws Exception {
        mvc.perform(
                        post("/api/payments/webhook")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("not-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_WEBHOOK_SIGNATURE"));
        String body = body("CONFIRMED");
        mvc.perform(
                        post("/api/payments/webhook")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body + " ")
                                .header("X-PG-Timestamp", timestamp)
                                .header("X-PG-Signature", sign(body)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_WEBHOOK_SIGNATURE"));
        verifyNoInteractions(payments);
    }

    @Test
    void signedUnknownStatusAndMalformedPayloadReturnValidationFailure() throws Exception {
        for (String body : new String[] {body("UNKNOWN"), "not-json", "null", "{}"})
            mvc.perform(
                            post("/api/payments/webhook")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body)
                                    .header("X-PG-Timestamp", timestamp)
                                    .header("X-PG-Signature", sign(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(payments);
    }

    @Test
    void oversizedWebhookCannotReachPaymentProcessing() throws Exception {
        String body = "x".repeat(16385);
        mvc.perform(
                        post("/api/payments/webhook")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                                .header("X-PG-Timestamp", timestamp)
                                .header("X-PG-Signature", sign(body)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
        verifyNoInteractions(payments);
    }

    private String body(String status) {
        return "{\"eventId\":\"evt\",\"orderNo\":\"O-1\",\"paymentKey\":\"key\",\"amount\":10,\"status\":\""
                + status
                + "\"}";
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
