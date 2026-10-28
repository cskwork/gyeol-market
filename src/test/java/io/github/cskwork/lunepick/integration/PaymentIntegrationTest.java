package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cskwork.lunepick.coupon.CouponApi;
import io.github.cskwork.lunepick.order.internal.application.OrderService;
import io.github.cskwork.lunepick.payment.internal.application.PaymentService;
import io.github.cskwork.lunepick.payment.internal.config.PaymentMaintenance;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        properties =
                "lunepick.payment.webhook-secret=payment-integration-synthetic-secret-32-bytes")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PaymentIntegrationTest extends EmbeddedDatabase {
    private static final String SECRET = "payment-integration-synthetic-secret-32-bytes";
    private final String timestamp = Long.toString(Instant.now().getEpochSecond());
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired CouponApi coupons;
    @Autowired PaymentService payments;
    @Autowired PaymentMaintenance maintenance;
    @Autowired OrderService orders;
    @Autowired MeterRegistry meters;

    @Test
    void concurrentDoubleConfirmSettlesOnce() throws Exception {
        var order = pendingOrder();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return payments.confirm(
                                        order.member(), order.no(), order.key(), order.amount());
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return payments.confirm(
                                        order.member(), order.no(), order.key(), order.amount());
                            });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(count("payment_confirmations", "order_id", order.id())).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM orders WHERE id=?", String.class, order.id()))
                .isEqualTo("PAID");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT point_balance FROM members WHERE id=?",
                                BigDecimal.class,
                                order.member()))
                .isEqualByComparingTo("4000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reserved_points FROM members WHERE id=?",
                                BigDecimal.class,
                                order.member()))
                .isEqualByComparingTo("0");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM member_coupon WHERE id=?",
                                String.class,
                                order.coupon()))
                .isEqualTo("USED");
        assertThat(count("cart_items", "member_id", order.member())).isZero();
    }

    @Test
    void amountKeyAndOwnerMismatchDoNotAlterReservationsOrCreatePaymentRows() throws Exception {
        var order = pendingOrder();
        for (String body :
                new String[] {
                    confirmBody(order, "wrong", order.amount()),
                    confirmBody(order, order.key(), BigDecimal.ONE)
                })
            mvc.perform(
                            post("/api/payments/confirm")
                                    .with(jwt().jwt(j -> j.subject(Long.toString(order.member()))))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.error.code").value("PAYMENT_AMOUNT_MISMATCH"));
        mvc.perform(
                        post("/api/payments/confirm")
                                .with(
                                        jwt().jwt(
                                                        j ->
                                                                j.subject(
                                                                        Long.toString(
                                                                                order.member()
                                                                                        + 999999))))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(confirmBody(order, order.key(), order.amount())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        assertThat(count("payment_confirmations", "order_id", order.id())).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM orders WHERE id=?", String.class, order.id()))
                .isEqualTo("PAYMENT_PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reserved_points FROM members WHERE id=?",
                                BigDecimal.class,
                                order.member()))
                .isEqualByComparingTo("1000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM member_coupon WHERE id=?",
                                String.class,
                                order.coupon()))
                .isEqualTo("ISSUED");
        assertThat(count("cart_items", "member_id", order.member())).isEqualTo(1);
    }

    @Test
    void failedWebhookReleasesReservationsReplaysAndRejectsAlteredEventId() throws Exception {
        var order = pendingOrder();
        String eventId = UUID.randomUUID().toString();
        String body = webhookBody(order, eventId, "FAILED");
        signedWebhook(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(true));
        signedWebhook(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(false));
        signedWebhook(webhookBody(order, eventId, "CANCELLED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WEBHOOK_EVENT_CONFLICT"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM orders WHERE id=?", String.class, order.id()))
                .isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT point_balance FROM members WHERE id=?",
                                BigDecimal.class,
                                order.member()))
                .isEqualByComparingTo("5000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reserved_points FROM members WHERE id=?",
                                BigDecimal.class,
                                order.member()))
                .isEqualByComparingTo("0");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reserved_order_id FROM member_coupon WHERE id=?",
                                Long.class,
                                order.coupon()))
                .isNull();
        assertThat(count("cart_items", "member_id", order.member())).isEqualTo(1);
        String retryEvent = UUID.randomUUID().toString();
        signedWebhook(webhookBody(order, retryEvent, "CONFIRMED")).andExpect(status().isOk());
        assertThat(count("payment_confirmations", "order_id", order.id())).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_webhook_events WHERE event_id=?",
                                Long.class,
                                retryEvent))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM payment_webhook_events WHERE event_id=?",
                                String.class,
                                retryEvent))
                .isEqualTo("NEEDS_REVIEW");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT order_no FROM payment_webhook_events WHERE event_id=?",
                                String.class,
                                retryEvent))
                .isEqualTo(order.no());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT event_status FROM payment_webhook_events WHERE event_id=?",
                                String.class,
                                retryEvent))
                .isEqualTo("CONFIRMED");
    }

    @Test
    void confirmedWebhookAndUserConfirmShareResultAndPaidOrderRejectsCancellation()
            throws Exception {
        var order = pendingOrder();
        signedWebhook(webhookBody(order, UUID.randomUUID().toString(), "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(true));
        mvc.perform(
                        post("/api/payments/confirm")
                                .with(jwt().jwt(j -> j.subject(Long.toString(order.member()))))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(confirmBody(order, order.key(), order.amount())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.paymentKey").value(order.key()));
        String event = UUID.randomUUID().toString();
        signedWebhook(webhookBody(order, event, "CANCELLED")).andExpect(status().isOk());
        assertThat(count("payment_confirmations", "order_id", order.id())).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_webhook_events WHERE event_id=?",
                                Long.class,
                                event))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM payment_webhook_events WHERE event_id=?",
                                String.class,
                                event))
                .isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void expiredOrderConfirmationReturnsTheClientStateErrorEnvelope() throws Exception {
        var order = pendingOrder();
        jdbc.update("UPDATE orders SET created_at=now()-interval '1 hour' WHERE id=?", order.id());
        assertThat(orders.expirePending(Duration.ofMinutes(30), 100)).isGreaterThanOrEqualTo(1);
        var response =
                mvc.perform(
                                post("/api/payments/confirm")
                                        .with(
                                                jwt().jwt(
                                                                j ->
                                                                        j.subject(
                                                                                Long.toString(
                                                                                        order
                                                                                                .member()))))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(confirmBody(order, order.key(), order.amount())))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.error.code").value("INVALID_ORDER_STATE"))
                        .andReturn();
        new ContractAssertions(mapper).check("error-invalid-order-state", response);
        assertThat(count("payment_confirmations", "order_id", order.id())).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM orders WHERE id=?", String.class, order.id()))
                .isEqualTo("CANCELLED");
    }

    @Test
    void webhookAmountAndKeyMismatchAreAcknowledgedLoggedAndCountedForReview(CapturedOutput output)
            throws Exception {
        var order = pendingOrder();
        double before = meters.counter("lunepick.payment.webhook.needs_review").count();
        for (String mismatch : new String[] {"PAYMENT_AMOUNT_MISMATCH", "PAYMENT_KEY_MISMATCH"}) {
            String eventId = UUID.randomUUID().toString();
            String body = webhookBody(order, eventId, "CONFIRMED");
            if (mismatch.equals("PAYMENT_AMOUNT_MISMATCH"))
                body = body.replace("\"amount\":" + order.amount(), "\"amount\":1");
            else
                body =
                        body.replace(
                                "\"paymentKey\":\"" + order.key() + "\"",
                                "\"paymentKey\":\"wrong-provider-key\"");
            signedWebhook(body)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.processed").value(true));
            signedWebhook(body)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.processed").value(false));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM payment_webhook_events WHERE event_id=?",
                                    String.class,
                                    eventId))
                    .isEqualTo("NEEDS_REVIEW");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT review_reason FROM payment_webhook_events WHERE event_id=?",
                                    String.class,
                                    eventId))
                    .isEqualTo(mismatch);
            assertThat(output)
                    .contains("orderId=" + order.id(), "eventId=" + eventId, "reason=" + mismatch);
        }
        assertThat(meters.counter("lunepick.payment.webhook.needs_review").count())
                .isEqualTo(before + 2);
        assertThat(count("order_reconciliation_holds", "order_id", order.id())).isEqualTo(1);
        assertThat(count("payment_confirmations", "order_id", order.id())).isZero();
        jdbc.update("UPDATE orders SET created_at=now()-interval '1 hour' WHERE id=?", order.id());
        assertThat(orders.expirePending(Duration.ofMinutes(30), 100)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM orders WHERE id=?", String.class, order.id()))
                .isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void retentionDeletesProcessedEventsAndPreservesUnresolvedReviews() {
        String processed = UUID.randomUUID().toString(), review = UUID.randomUUID().toString();
        jdbc.update(
                "insert into payment_webhook_events(event_id,payload_hash,processed_at,status)"
                        + " values(?,'hash',now()-interval '31"
                        + " days','PROCESSED'),(?,'hash',now()-interval '31 days','NEEDS_REVIEW')",
                processed,
                review);
        maintenance.cleanup();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment_webhook_events where event_id=?",
                                Long.class,
                                processed))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select status from payment_webhook_events where event_id=?",
                                String.class,
                                review))
                .isEqualTo("NEEDS_REVIEW");
    }

    private Pending pendingOrder() throws Exception {
        long member =
                jdbc.queryForObject(
                        "INSERT INTO members(email,password_hash,name,created_at) VALUES"
                                + " (?,'unused','결제검증',now()) RETURNING id",
                        Long.class,
                        "payment-" + UUID.randomUUID() + "@example.test");
        jdbc.update(
                "INSERT INTO member_coupon(member_id,coupon_id,status,issued_at,expires_at)"
                        + " SELECT ?,id,'ISSUED',now(),now()+interval '90 days' FROM coupon",
                member);
        long address =
                jdbc.queryForObject(
                        """
                        INSERT INTO addresses(member_id,recipient_name,phone,zipcode,address_main,is_default)
                        VALUES (?,'수령인','01000000000','00000','테스트 주소',true) RETURNING id
                        """,
                        Long.class,
                        member);
        long coupon = coupons.list(member).getFirst().id();
        jdbc.update("INSERT INTO cart_items(member_id,option_id,quantity) VALUES (?,1,1)", member);
        String body =
                "{\"items\":[{\"productOptionId\":1,\"quantity\":1}],\"couponId\":"
                        + coupon
                        + ",\"usePointAmount\":1000,\"deliveryAddressId\":"
                        + address
                        + "}";
        var result =
                mvc.perform(
                                post("/api/orders")
                                        .with(jwt().jwt(j -> j.subject(Long.toString(member))))
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isOk())
                        .andReturn();
        var data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        String no = data.get("orderNo").asText();
        long id = jdbc.queryForObject("SELECT id FROM orders WHERE order_no=?", Long.class, no);
        return new Pending(
                member,
                id,
                no,
                data.get("paymentKey").asText(),
                data.get("amount").decimalValue(),
                coupon);
    }

    private ResultActions signedWebhook(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        String signature =
                HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        return mvc.perform(
                post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-PG-Timestamp", timestamp)
                        .header("X-PG-Signature", signature));
    }

    private String confirmBody(Pending order, String key, BigDecimal amount) {
        return "{\"orderNo\":\""
                + order.no()
                + "\",\"paymentKey\":\""
                + key
                + "\",\"amount\":"
                + amount
                + "}";
    }

    private String webhookBody(Pending order, String event, String status) {
        return "{\"eventId\":\""
                + event
                + "\",\"orderNo\":\""
                + order.no()
                + "\",\"paymentKey\":\""
                + order.key()
                + "\",\"amount\":"
                + order.amount()
                + ",\"status\":\""
                + status
                + "\"}";
    }

    private long count(String table, String field, long id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + field + "=?", Long.class, id);
    }

    private record Pending(
            long member, long id, String no, String key, BigDecimal amount, long coupon) {}
}
