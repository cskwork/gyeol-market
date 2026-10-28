package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommerceContractIntegrationTest extends EmbeddedDatabase {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    String access;
    ContractAssertions contracts;

    MvcResult call(String method, String path, Object body, String key) throws Exception {
        var request = request(method, URI.create(path)).header("Authorization", "Bearer " + access);
        if (body != null)
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body));
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request).andReturn();
    }

    MvcResult fixture(String name, String path, Object body, String key) throws Exception {
        var fixture = contracts.fixture(name);
        var result =
                call(
                        fixture.get("method").asText(),
                        path == null ? fixture.get("path").asText() : path,
                        body == null ? fixture.get("request") : body,
                        key);
        contracts.check(name, result);
        return result;
    }

    @Test
    void fullShopperJourneyAndEveryCommerceWireFixtureMatchActualResponses() throws Exception {
        contracts = new ContractAssertions(mapper);
        var credentials =
                Map.of("email", "contract-shopper@example.test", "password", "contract1234");
        var signup = new LinkedHashMap<String, Object>(credentials);
        signup.put("name", "계약 검증");
        assertThat(call("POST", "/api/auth/signup", signup, null).getResponse().getStatus())
                .isEqualTo(201);
        var login = call("POST", "/api/auth/login", credentials, null);
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        access = contracts.response(login).at("/data/accessToken").asText();
        fixture("error-product-option-not-found", null, null, null);
        fixture("error-cart-item-not-found", null, null, null);
        var cartAdded = fixture("cart-add", null, null, null);
        long cartItem = contracts.response(cartAdded).at("/data/cartItemId").asLong();
        fixture("cart-update", "/api/cart/items/" + cartItem, null, null);
        var cart = fixture("cart", null, null, null);
        assertThat(contracts.response(cart).at("/data/totalAmount").decimalValue())
                .isEqualByComparingTo("50400");
        assertThat(contracts.response(cart).at("/data/items/0/productId").asLong()).isEqualTo(3);
        assertThat(contracts.response(cart).at("/data/items/0/thumbnailUrl").asText())
                .isEqualTo("/images/products/dermaharang-calming-cica-cream-70ml.png");
        fixture("cart-merge", null, null, null);
        // Delete a separate line so the order can later demonstrate purchased-line cleanup.
        var extra =
                call("POST", "/api/cart/items", Map.of("productOptionId", 1, "quantity", 1), null);
        long extraId = contracts.response(extra).at("/data/cartItemId").asLong();
        fixture("cart-delete", "/api/cart/items/" + extraId, null, null);
        var address = fixture("address-create", null, null, null);
        long addressId = contracts.response(address).at("/data/id").asLong();
        fixture("addresses", null, null, null);
        var coupons = fixture("coupons", null, null, null);
        long couponId = 0;
        for (var coupon : contracts.response(coupons).get("data"))
            if (coupon.get("couponId").asLong() == 2) couponId = coupon.get("id").asLong();
        assertThat(couponId).isPositive();
        fixture("wishlist-add", null, null, null);
        fixture("wishlist", null, null, null);
        fixture("wishlist-delete", null, null, null);
        ObjectNode body = (ObjectNode) contracts.fixture("order-create").get("request").deepCopy();
        body.put("deliveryAddressId", addressId);
        body.put("couponId", couponId);
        fixture("error-idempotency-required", "/api/orders", body, null);
        var badAddress = body.deepCopy().put("deliveryAddressId", 999999);
        fixture("error-address-not-found", "/api/orders", badAddress, "missing-address");
        fixture(
                "error-coupon-invalid",
                "/api/orders",
                body.deepCopy().put("couponId", 999999),
                "bad-coupon");
        fixture(
                "error-insufficient-points",
                "/api/orders",
                body.deepCopy().put("usePointAmount", 99999999),
                "bad-points");
        jdbc.update("update product_option set available_quantity=1 where id=4");
        try {
            fixture("error-insufficient-stock", "/api/orders", body, "last-stock");
        } finally {
            jdbc.update("update product_option set available_quantity=NULL where id=4");
        }
        var created = fixture("order-create", null, body, "shopper-order");
        var order = contracts.response(created).get("data");
        assertThat(order.get("amount").decimalValue()).isEqualByComparingTo("44360");
        var replay = fixture("order-create", null, body, "shopper-order");
        assertThat(contracts.response(replay)).isEqualTo(contracts.response(created));
        fixture(
                "error-idempotency-conflict",
                "/api/orders",
                body.deepCopy().put("usePointAmount", 0),
                "shopper-order");
        fixture("error-order-not-found", null, null, null);
        Map<String, Object> payment =
                Map.of(
                        "orderNo",
                        order.get("orderNo").asText(),
                        "paymentKey",
                        order.get("paymentKey").asText(),
                        "amount",
                        44360);
        var badAmount = new LinkedHashMap<>(payment);
        badAmount.put("amount", 1);
        fixture(
                "error-payment-amount-mismatch",
                "/api/payments/confirm",
                badAmount,
                "payment-mismatch");
        var paid = fixture("payment-confirm", null, payment, "payment-confirm");
        var paidReplay = fixture("payment-confirm", null, payment, "payment-confirm");
        assertThat(contracts.response(paidReplay)).isEqualTo(contracts.response(paid));
        fixture("orders", null, null, null);
        fixture("order-detail", "/api/orders/" + order.get("orderNo").asText(), null, null);
        var summary = fixture("my-summary", null, null, null);
        assertThat(contracts.response(summary).at("/data/name").asText()).isEqualTo("계약 검증");
        assertThat(contracts.response(summary).at("/data/pointBalance").decimalValue())
                .isEqualByComparingTo("4000");
        assertThat(contracts.response(summary).at("/data/usableCouponCount").asInt()).isEqualTo(2);
        assertThat(contracts.response(summary).at("/data/totalOrderCount").asInt()).isEqualTo(1);
        assertThat(
                        contracts
                                .response(call("GET", "/api/cart", null, null))
                                .at("/data/items")
                                .size())
                .isZero();
        fixture("error-webhook-signature", null, null, null);
        Map<String, Object> event = new LinkedHashMap<>(payment);
        event.put("eventId", "contract-webhook-event");
        event.put("status", "CONFIRMED");
        byte[] raw = mapper.writeValueAsBytes(event);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(
                new SecretKeySpec(
                        "lunepick-local-webhook-secret-32-bytes-minimum"
                                .getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256"));
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        String signature = HexFormat.of().formatHex(mac.doFinal(raw));
        var callback =
                mvc.perform(
                                post("/api/payments/webhook")
                                        .header("X-PG-Signature", signature)
                                        .header("X-PG-Timestamp", timestamp)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(raw))
                        .andReturn();
        contracts.check("webhook-ack", callback);
        assertThat(contracts.response(callback).at("/data/processed").asBoolean()).isTrue();
        var duplicate =
                mvc.perform(
                                post("/api/payments/webhook")
                                        .header("X-PG-Signature", signature)
                                        .header("X-PG-Timestamp", timestamp)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(raw))
                        .andReturn();
        contracts.check("webhook-ack", duplicate);
        assertThat(contracts.response(duplicate).at("/data/processed").asBoolean()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment_webhook_events where"
                                        + " event_id='contract-webhook-event'",
                                Integer.class))
                .isEqualTo(1);
    }
}
