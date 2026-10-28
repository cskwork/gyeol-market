package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cskwork.lunepick.coupon.CouponApi;
import io.github.cskwork.lunepick.order.OrderApi;
import io.github.cskwork.lunepick.order.internal.application.OrderDtos.CreateRequest;
import io.github.cskwork.lunepick.order.internal.application.OrderDtos.ItemRequest;
import io.github.cskwork.lunepick.order.internal.application.OrderService;
import io.github.cskwork.lunepick.order.internal.config.OrderExpiryConfiguration;
import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OrderIntegrationTest.TimeConfiguration.class)
class OrderIntegrationTest extends EmbeddedDatabase {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderApi orders;
    @Autowired CouponApi coupons;
    @Autowired OrderService orderService;
    @Autowired OrderExpiryConfiguration expiry;
    @Autowired ExpiryClock clock;
    @Autowired PlatformTransactionManager transactions;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        ExpiryClock expiryClock() {
            return new ExpiryClock();
        }
    }

    static class ExpiryClock extends Clock {
        Instant now = Instant.now();

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    long member;
    long address;

    @BeforeEach
    void fixture() {
        clock.now = Instant.now();
        member =
                jdbc.queryForObject(
                        "insert into members(email,password_hash,name,created_at)"
                                + " values(?,?,?,now()) returning id",
                        Long.class,
                        UUID.randomUUID() + "@order.example",
                        "unused",
                        "order-member");
        jdbc.update(
                "INSERT INTO member_coupon(member_id,coupon_id,status,issued_at,expires_at)"
                        + " SELECT ?,id,'ISSUED',now(),now()+interval '90 days' FROM coupon",
                member);
        address =
                jdbc.queryForObject(
                        "insert into"
                                + " addresses(member_id,recipient_name,phone,zipcode,address_main,address_detail,is_default)"
                                + " values(?,'원래 수령인','01012345678','12345','원래 주소','상세',true)"
                                + " returning id",
                        Long.class,
                        member);
    }

    Map<String, Object> body(long option, Object quantity, long points, Long coupon) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(Map.of("productOptionId", option, "quantity", quantity)));
        body.put("deliveryAddressId", address);
        body.put("usePointAmount", points);
        if (coupon != null) body.put("couponId", coupon);
        return body;
    }

    MvcResult create(String key, Map<String, Object> body) throws Exception {
        var request =
                post("/api/orders")
                        .with(jwt().jwt(j -> j.subject(Long.toString(member))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body));
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request).andReturn();
    }

    JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void freeShippingCouponValidatesMinimumThenCombinesWithPoints() throws Exception {
        long coupon =
                coupons.list(member).stream()
                        .filter(c -> c.discountType().equals("FREE_SHIPPING"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        // Option 6 is the 9,900-won cleansing foam, below the coupon's 15,000-won minimum.
        var rejected = create("shipping-below-minimum", body(6, 1, 5000, coupon));
        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(rejected).at("/error/code").asText()).isEqualTo("COUPON_INVALID");
        // Option 1 is 20,000 won: the 3,000-won fee is waived, then 5,000 points apply.
        var accepted = create("shipping-with-points", body(1, 1, 5000, coupon));
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        var created = json(accepted).get("data");
        var detail = orderService.detail(member, created.get("orderNo").asText());
        assertThat(detail.deliveryFee()).isZero();
        assertThat(detail.discountAmount()).isZero();
        assertThat(detail.pointUsedAmount()).isEqualByComparingTo("5000");
        assertThat(detail.finalPaymentAmount()).isEqualByComparingTo("15000");
    }

    @Test
    void expiryReleasesPointsCouponAndFiniteStockOnceAndKeepsCart() throws Exception {
        long option =
                jdbc.queryForObject(
                        "insert into"
                                + " product_option(id,product_id,name,price,status,available_quantity,sort_order)"
                                + " values(nextval('order_payment_key_seq'),1,'expiry',0,'ON_SALE',1,99)"
                                + " returning id",
                        Long.class);
        long coupon = coupons.list(member).getFirst().id();
        jdbc.update(
                "insert into cart_items(member_id,option_id,quantity) values(?,?,1)",
                member,
                option);
        var data = json(create("expire", body(option, 1, 1000, coupon))).get("data");
        String no = data.get("orderNo").asText();
        assertThat(orderService.summary(member).pointBalance()).isEqualByComparingTo("4000");
        clock.now = clock.now.plus(Duration.ofMinutes(29));
        expiry.expire();
        assertThat(orderService.detail(member, no).status()).isEqualTo("PAYMENT_PENDING");
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        expiry.expire();
        expiry.expire();
        assertThat(orderService.detail(member, no).status()).isEqualTo("CANCELLED");
        assertThat(orderService.summary(member).pointBalance()).isEqualByComparingTo("5000");
        assertThat(
                        jdbc.queryForObject(
                                "select available_quantity from product_option where id=?",
                                Long.class,
                                option))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select reserved_order_id from member_coupon where id=?",
                                Long.class,
                                coupon))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from cart_items where member_id=?",
                                Long.class,
                                member))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                orders.confirm(
                                        member,
                                        no,
                                        data.get("paymentKey").asText(),
                                        data.get("amount").decimalValue()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void expirySkipsAnOrderLockedByAnotherTransaction() throws Exception {
        var data = json(create("locked-expire", body(1, 1, 100, null))).get("data");
        String no = data.get("orderNo").asText();
        clock.now = clock.now.plus(Duration.ofMinutes(31));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactions)
                                            .execute(
                                                    status -> {
                                                        jdbc.queryForObject(
                                                                "select id from orders where"
                                                                        + " order_no=? for update",
                                                                Long.class,
                                                                no);
                                                        locked.countDown();
                                                        try {
                                                            if (!release.await(
                                                                    10, TimeUnit.SECONDS))
                                                                throw new IllegalStateException(
                                                                        "lock wait timed out");
                                                        } catch (InterruptedException e) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(e);
                                                        }
                                                        return null;
                                                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                expiry.expire();
                assertThat(orderService.detail(member, no).status()).isEqualTo("PAYMENT_PENDING");
            } finally {
                release.countDown();
            }
            worker.get(10, TimeUnit.SECONDS);
        }
        expiry.expire();
        assertThat(orderService.detail(member, no).status()).isEqualTo("CANCELLED");
    }

    @Test
    void expiryCommitsEachOrderBeforeWaitingOnAnotherMemberAndConcurrentCheckoutCompletes()
            throws Exception {
        long otherMember =
                jdbc.queryForObject(
                        "INSERT INTO members(email,password_hash,name,created_at) VALUES(?,'unused','concurrent',now()) RETURNING id",
                        Long.class,
                        UUID.randomUUID() + "@expiry.example");
        long otherAddress =
                jdbc.queryForObject(
                        "INSERT INTO addresses(member_id,recipient_name,phone,zipcode,address_main,is_default) VALUES(?,'동시','01012345678','12345','주소',true) RETURNING id",
                        Long.class,
                        otherMember);
        long firstOption =
                jdbc.queryForObject(
                        "INSERT INTO product_option(id,product_id,name,price,status,available_quantity,sort_order) VALUES(nextval('order_payment_key_seq'),1,'first',0,'ON_SALE',10,99) RETURNING id",
                        Long.class);
        long secondOption =
                jdbc.queryForObject(
                        "INSERT INTO product_option(id,product_id,name,price,status,available_quantity,sort_order) VALUES(nextval('order_payment_key_seq'),1,'second',0,'ON_SALE',10,99) RETURNING id",
                        Long.class);
        var first =
                orderService.create(
                        member,
                        "expire-first",
                        new CreateRequest(
                                List.of(new ItemRequest(firstOption, BigDecimal.ONE)),
                                null,
                                BigDecimal.valueOf(100),
                                address));
        var second =
                orderService.create(
                        otherMember,
                        "expire-second",
                        new CreateRequest(
                                List.of(new ItemRequest(secondOption, BigDecimal.ONE)),
                                null,
                                BigDecimal.valueOf(100),
                                otherAddress));
        clock.now = clock.now.plus(Duration.ofMinutes(31));
        var memberLocked = new CountDownLatch(1);
        var firstCommitted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var create =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactions)
                                            .execute(
                                                    status -> {
                                                        jdbc.execute("SET LOCAL lock_timeout='8s'");
                                                        jdbc.update(
                                                                "UPDATE members SET reserved_points=reserved_points WHERE id=?",
                                                                otherMember);
                                                        memberLocked.countDown();
                                                        try {
                                                            if (!firstCommitted.await(
                                                                    10, TimeUnit.SECONDS))
                                                                throw new IllegalStateException(
                                                                        "First expiry did not commit independently");
                                                        } catch (InterruptedException error) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(error);
                                                        }
                                                        return orderService.create(
                                                                otherMember,
                                                                "overlapping-checkout",
                                                                new CreateRequest(
                                                                        List.of(
                                                                                new ItemRequest(
                                                                                        firstOption,
                                                                                        BigDecimal
                                                                                                .ONE)),
                                                                        null,
                                                                        BigDecimal.valueOf(100),
                                                                        otherAddress));
                                                    }));
            assertThat(memberLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var expire =
                    executor.submit(() -> orderService.expirePending(Duration.ofMinutes(30), 1000));
            try {
                Awaitility.await()
                        .atMost(Duration.ofSeconds(10))
                        .untilAsserted(
                                () ->
                                        assertThat(
                                                        orderService
                                                                .detail(member, first.orderNo())
                                                                .status())
                                                .isEqualTo("CANCELLED"));
            } finally {
                firstCommitted.countDown();
            }
            var created = create.get(10, TimeUnit.SECONDS);
            assertThat(expire.get(10, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(2);
            assertThat(orderService.detail(otherMember, second.orderNo()).status())
                    .isEqualTo("CANCELLED");
            assertThat(orderService.detail(otherMember, created.orderNo()).status())
                    .isEqualTo("PAYMENT_PENDING");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available_quantity FROM product_option WHERE id=?",
                                Long.class,
                                firstOption))
                .isEqualTo(9);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available_quantity FROM product_option WHERE id=?",
                                Long.class,
                                secondOption))
                .isEqualTo(10);
    }

    @Test
    void replayRetentionClearsOnlyTerminalKeysAndPreservesOrderHistory() throws Exception {
        var data = json(create("retained", body(1, 1, 0, null))).get("data");
        String no = data.get("orderNo").asText();
        orders.fail(
                no, data.get("paymentKey").asText(), data.get("amount").decimalValue(), "FAILED");
        create("pending-retained", body(1, 1, 0, null));
        clock.now = clock.now.plus(Duration.ofHours(25));
        expiry.cleanup();
        assertThat(orderService.detail(member, no).status()).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "select idempotency_key from orders where order_no=?",
                                String.class,
                                no))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from orders where member_id=? and"
                                        + " idempotency_key='pending-retained'",
                                Long.class,
                                member))
                .isEqualTo(1);
        assertThat(json(create("retained", body(1, 1, 0, null))).at("/data/orderNo").asText())
                .isNotEqualTo(no);
    }

    @Test
    void preservesContractSnapshotsAndSettlesOnlyOnPaymentOnce() throws Exception {
        long coupon =
                coupons.list(member).stream()
                        .filter(c -> c.couponId() == 1)
                        .findFirst()
                        .orElseThrow()
                        .id();
        jdbc.update("insert into cart_items(member_id,option_id,quantity) values(?,1,1)", member);
        var created = create("snapshot", body(1, 1, 1000, coupon));
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = json(created).get("data");
        assertThat(data.get("paymentKey").isNumber()).isTrue();
        assertThat(data.get("pgCheckoutPayload").isNull()).isTrue();
        String no = data.get("orderNo").asText();
        assertThat(
                        jdbc.queryForObject(
                                "select point_balance from members where id=?",
                                BigDecimal.class,
                                member))
                .isEqualByComparingTo("5000");
        assertThat(
                        jdbc.queryForObject(
                                "select reserved_points from members where id=?",
                                BigDecimal.class,
                                member))
                .isEqualByComparingTo("1000");
        assertThat(
                        jdbc.queryForObject(
                                "select status from member_coupon where id=?",
                                String.class,
                                coupon))
                .isEqualTo("ISSUED");
        jdbc.update(
                "update addresses set recipient_name='바뀐 수령인',address_main='바뀐 주소' where id=?",
                address);
        mvc.perform(get("/api/orders/" + no).with(jwt().jwt(j -> j.subject(Long.toString(member)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivery.recipientName").value("원래 수령인"))
                .andExpect(jsonPath("$.data.delivery.addressMain").value("원래 주소"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(1));
        orders.confirm(
                member, no, data.get("paymentKey").asText(), data.get("amount").decimalValue());
        orders.confirm(
                member, no, data.get("paymentKey").asText(), data.get("amount").decimalValue());
        assertThat(
                        jdbc.queryForObject(
                                "select point_balance from members where id=?",
                                BigDecimal.class,
                                member))
                .isEqualByComparingTo("4000");
        assertThat(
                        jdbc.queryForObject(
                                "select reserved_points from members where id=?",
                                BigDecimal.class,
                                member))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from cart_items where member_id=?",
                                Long.class,
                                member))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select status from member_coupon where id=?",
                                String.class,
                                coupon))
                .isEqualTo("USED");
        mvc.perform(get("/api/me/summary").with(jwt().jwt(j -> j.subject(Long.toString(member)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalOrderCount").value(1))
                .andExpect(jsonPath("$.data.gradeName").value("BRONZE"));
        mvc.perform(
                        get("/api/orders/" + no)
                                .with(jwt().jwt(j -> j.subject(Long.toString(member + 999999)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        mvc.perform(
                        get("/api/orders")
                                .param("status", "PAID")
                                .with(jwt().jwt(j -> j.subject(Long.toString(member)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1));
    }

    @Test
    void sameKeyRaceCreatesOneOrderAndConflictingBodyCannotReserveAnything() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Map<String, Object> body = body(1, 1, 1000, null);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<MvcResult> task =
                    () -> {
                        start.await();
                        return create("race", body);
                    };
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            var a = first.get(20, TimeUnit.SECONDS);
            var b = second.get(20, TimeUnit.SECONDS);
            assertThat(a.getResponse().getStatus()).isEqualTo(200);
            assertThat(b.getResponse().getStatus()).isEqualTo(200);
            assertThat(json(a)).isEqualTo(json(b));
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from orders where member_id=?",
                                Long.class,
                                member))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select reserved_points from members where id=?",
                                BigDecimal.class,
                                member))
                .isEqualByComparingTo("1000");
        var conflict = create("race", body(1, 2, 1000, null));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(conflict).at("/error/code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void lastUnitRacePreventsOversellAndFailureReleasesOnce() throws Exception {
        long option =
                jdbc.queryForObject(
                        "insert into"
                                + " product_option(id,product_id,name,price,status,available_quantity,sort_order)"
                                + " values(nextval('order_payment_key_seq'),1,'race"
                                + " option',0,'ON_SALE',1,99) returning id",
                        Long.class);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<MvcResult> results;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first =
                        executor.submit(
                                () -> {
                                    start.await();
                                    return create("unit-a", body(option, 1, 100, null));
                                });
                var second =
                        executor.submit(
                                () -> {
                                    start.await();
                                    return create("unit-b", body(option, 1, 100, null));
                                });
                start.countDown();
                results =
                        List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            }
            assertThat(results.stream().map(r -> r.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(
                            jdbc.queryForObject(
                                    "select available_quantity from product_option where id=?",
                                    Long.class,
                                    option))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from orders where member_id=?",
                                    Long.class,
                                    member))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "select reserved_points from members where id=?",
                                    BigDecimal.class,
                                    member))
                    .isEqualByComparingTo("100");
            var winner =
                    json(results.stream()
                                    .filter(r -> r.getResponse().getStatus() == 200)
                                    .findFirst()
                                    .orElseThrow())
                            .get("data");
            orders.fail(
                    winner.get("orderNo").asText(),
                    winner.get("paymentKey").asText(),
                    winner.get("amount").decimalValue(),
                    "FAILED");
            orders.fail(
                    winner.get("orderNo").asText(),
                    winner.get("paymentKey").asText(),
                    winner.get("amount").decimalValue(),
                    "FAILED");
            assertThat(
                            jdbc.queryForObject(
                                    "select available_quantity from product_option where id=?",
                                    Long.class,
                                    option))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "select reserved_points from members where id=?",
                                    BigDecimal.class,
                                    member))
                    .isZero();
        } finally {
            // Keep the catalog fixture: immutable order snapshots deliberately have no foreign key
            // to it.
            jdbc.update("update product_option set status='HIDDEN' where id=?", option);
        }
    }

    @Test
    void failedCouponRollsBackIdempotencyStockAndPointsAndKeyCanRetry() throws Exception {
        long option =
                jdbc.queryForObject(
                        "insert into"
                                + " product_option(id,product_id,name,price,status,available_quantity,sort_order)"
                                + " values(nextval('order_payment_key_seq'),1,'rollback"
                                + " option',0,'ON_SALE',1,99) returning id",
                        Long.class);
        var bad = create("retry", body(option, 1, 100, 999999L));
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(bad).at("/error/code").asText()).isEqualTo("COUPON_INVALID");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from orders where member_id=?",
                                Long.class,
                                member))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select reserved_points from members where id=?",
                                BigDecimal.class,
                                member))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select available_quantity from product_option where id=?",
                                Long.class,
                                option))
                .isEqualTo(1);
        assertThat(create("retry", body(option, 1, 100, null)).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "select available_quantity from product_option where id=?",
                                Long.class,
                                option))
                .isZero();
        var fractional = create("fraction", body(1, new BigDecimal("1.5"), 0, null));
        assertThat(fractional.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(fractional).at("/error/code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(json(create(null, body(1, 1, 0, null))).at("/error/code").asText())
                .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }
}
