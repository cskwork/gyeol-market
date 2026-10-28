package io.github.cskwork.lunepick.order.internal.application;

import static org.assertj.core.api.Assertions.*;

import io.github.cskwork.lunepick.integration.EmbeddedDatabase;
import io.github.cskwork.lunepick.order.internal.application.OrderDtos.*;
import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.PageQuery;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Behaviour checks use a real transaction and database, so rollback is part of the assertion. */
@SpringBootTest
@ActiveProfiles("test")
class OrderServiceTest extends EmbeddedDatabase {
    @Autowired OrderService service;
    @Autowired JdbcTemplate jdbc;
    long member;
    long address;
    long option;
    long coupon;

    @BeforeEach
    void fixture() {
        member =
                jdbc.queryForObject(
                        "insert into members(email,password_hash,name,created_at)"
                                + " values(?,'unused','service',now()) returning id",
                        Long.class,
                        UUID.randomUUID() + "@example.test");
        address =
                jdbc.queryForObject(
                        "insert into"
                                + " addresses(member_id,recipient_name,phone,zipcode,address_main,is_default)"
                                + " values(?,'before','01000000000','00000','original',true) returning"
                                + " id",
                        Long.class,
                        member);
        option =
                jdbc.queryForObject(
                        "insert into"
                                + " product_option(id,product_id,name,price,status,available_quantity,sort_order)"
                                + " values(nextval('order_payment_key_seq'),1,'service',0,'ON_SALE',10,99)"
                                + " returning id",
                        Long.class);
        coupon =
                jdbc.queryForObject(
                        "insert into member_coupon(member_id,coupon_id,status,issued_at,expires_at)"
                                + " values(?,1,'ISSUED',now(),now()+interval '90 days') returning id",
                        Long.class,
                        member);
        jdbc.update(
                "insert into cart_items(member_id,option_id,quantity) values(?,?,2)",
                member,
                option);
    }

    CreateRequest request(Long selectedCoupon, BigDecimal points) {
        return new CreateRequest(
                List.of(new ItemRequest(option, new BigDecimal("2"))),
                selectedCoupon,
                points,
                address);
    }

    BigDecimal reserved() {
        return jdbc.queryForObject(
                "select reserved_points from members where id=?", BigDecimal.class, member);
    }

    long stock() {
        return jdbc.queryForObject(
                "select available_quantity from product_option where id=?", Long.class, option);
    }

    @Test
    void createsImmutableSnapshotsAndReservationsWithoutSettling() {
        var created = service.create(member, "create", request(coupon, new BigDecimal("100")));
        assertThat(created.pgCheckoutPayload()).isNull();
        assertThat(created.paymentKey()).isPositive();
        assertThat(reserved()).isEqualByComparingTo("100");
        assertThat(stock()).isEqualTo(8);
        assertThat(
                        jdbc.queryForObject(
                                "select point_balance from members where id=?",
                                BigDecimal.class,
                                member))
                .isEqualByComparingTo("5000");
        jdbc.update("update addresses set recipient_name='changed' where id=?", address);
        var detail = service.detail(member, created.orderNo());
        assertThat(detail.delivery().recipientName()).isEqualTo("before");
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().getFirst().quantity()).isEqualTo(2);
        assertThat(detail.status()).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void canonicalRequestReplaysAndDifferentBodyConflictsWithoutAdditionalReservations() {
        var first = service.create(member, "same", request(null, new BigDecimal("100")));
        assertThat(service.create(member, "same", request(null, new BigDecimal("100.0"))))
                .isEqualTo(first);
        assertThatThrownBy(() -> service.create(member, "same", request(null, BigDecimal.ZERO)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(reserved()).isEqualByComparingTo("100");
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void repeatedOptionsReserveTheirTotalAndKeepLineSnapshots() {
        var created =
                service.create(
                        member,
                        "repeat",
                        new CreateRequest(
                                List.of(
                                        new ItemRequest(option, BigDecimal.ONE),
                                        new ItemRequest(option, new BigDecimal("2"))),
                                null,
                                null,
                                address));
        assertThat(stock()).isEqualTo(7);
        assertThat(service.detail(member, created.orderNo()).items()).hasSize(2);
    }

    @Test
    void invalidInputCannotCreateAnOrderOrReserveAnything() {
        for (String key : new String[] {null, " ", "x".repeat(201)})
            assertThatThrownBy(() -> service.create(member, key, request(null, null)))
                    .isInstanceOf(ApiException.class);
        List<CreateRequest> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(new CreateRequest(null, null, null, address));
        invalid.add(new CreateRequest(List.of(), null, null, address));
        invalid.add(
                new CreateRequest(
                        List.of(new ItemRequest(option, BigDecimal.ONE)), null, null, null));
        invalid.add(new CreateRequest(Arrays.asList((ItemRequest) null), null, null, address));
        invalid.add(
                new CreateRequest(
                        List.of(new ItemRequest(null, BigDecimal.ONE)), null, null, address));
        invalid.add(
                new CreateRequest(
                        List.of(new ItemRequest(option, new BigDecimal("1.5"))),
                        null,
                        null,
                        address));
        invalid.add(request(null, new BigDecimal("-1")));
        for (var body : invalid)
            assertThatThrownBy(() -> service.create(member, "invalid", body))
                    .isInstanceOf(ApiException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from orders where member_id=?",
                                Long.class,
                                member))
                .isZero();
        assertThat(reserved()).isZero();
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void missingOptionAndExcessivePointsRollBackTheOrderAttempt() {
        var missing =
                new CreateRequest(
                        List.of(new ItemRequest(Long.MAX_VALUE, BigDecimal.ONE)),
                        null,
                        BigDecimal.TEN,
                        address);
        assertThatThrownBy(() -> service.create(member, "retry", missing))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        member, "retry", request(null, new BigDecimal("99999999"))))
                .isInstanceOf(ApiException.class);
        assertThat(reserved()).isZero();
        assertThat(stock()).isEqualTo(10);
        assertThat(service.create(member, "retry", request(null, BigDecimal.TEN)).orderNo())
                .isNotBlank();
    }

    @Test
    void confirmationSettlesExactlyOnceAndRejectsWrongOwnerAmountAndTerminalOrder() {
        var created = service.create(member, "confirm", request(coupon, new BigDecimal("100")));
        String key = Long.toString(created.paymentKey());
        assertThatThrownBy(
                        () ->
                                service.confirm(
                                        member + 99999, created.orderNo(), key, created.amount()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () -> service.confirm(member, created.orderNo(), "wrong", created.amount()))
                .isInstanceOf(ApiException.class);
        assertThat(service.confirm(member, created.orderNo(), key, created.amount()).status())
                .isEqualTo("PAID");
        service.confirm(member, created.orderNo(), key, created.amount());
        assertThat(
                        jdbc.queryForObject(
                                "select point_balance from members where id=?",
                                BigDecimal.class,
                                member))
                .isEqualByComparingTo("4900");
        assertThat(reserved()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select status from member_coupon where id=?",
                                String.class,
                                coupon))
                .isEqualTo("USED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from cart_items where member_id=?",
                                Long.class,
                                member))
                .isZero();
        assertThatThrownBy(() -> service.fail(created.orderNo(), key, created.amount(), "FAILED"))
                .isInstanceOf(ApiException.class);
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void failureReleasesExactlyOnceAndNeverRefundsPaidOrders() {
        var created = service.create(member, "fail", request(coupon, new BigDecimal("100")));
        String key = Long.toString(created.paymentKey());
        service.fail(created.orderNo(), key, created.amount(), "FAILED");
        service.fail(created.orderNo(), key, created.amount(), "FAILED");
        assertThat(stock()).isEqualTo(10);
        assertThat(reserved()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select reserved_order_id from member_coupon where id=?",
                                Long.class,
                                coupon))
                .isNull();
        assertThatThrownBy(() -> service.confirm(member, created.orderNo(), key, created.amount()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () -> service.fail(created.orderNo(), key, created.amount(), "CANCELLED"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.fail(created.orderNo(), key, created.amount(), "PAID"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void viewsAreOwnerScopedAndSummaryExcludesReservedPoints() {
        var created = service.create(member, "view", request(null, new BigDecimal("100")));
        assertThat(service.summary(member).pointBalance()).isEqualByComparingTo("4900");
        assertThat(service.summary(member).name()).isEqualTo("service");
        assertThat(service.list(member, null, new PageQuery(0, 10)).items()).hasSize(1);
        assertThat(service.list(member, "PAID", new PageQuery(0, 10)).items()).isEmpty();
        assertThatThrownBy(() -> service.detail(member + 99999, created.orderNo()))
                .isInstanceOf(ApiException.class);
    }
}
