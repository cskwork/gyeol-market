package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cskwork.lunepick.coupon.CouponApi;
import io.github.cskwork.lunepick.member.MemberRegistered;
import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CouponIntegrationTest extends EmbeddedDatabase {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    @Autowired CouponApi coupons;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired CouponClock clock;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate transactions;
    private final List<Long> members = new ArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        CouponClock couponClock() {
            return new CouponClock();
        }
    }

    static class CouponClock extends Clock {
        volatile Instant now = NOW;

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

    @AfterEach
    void cleanup() {
        clock.now = NOW;
        for (long member : members) {
            jdbc.update("DELETE FROM member_coupon WHERE member_id = ?", member);
            jdbc.update("DELETE FROM members WHERE id = ?", member);
        }
    }

    @Test
    void signupOutboxIssuesThreeSeedDefinitionsOnceWithNinetyDayExpiryAndFullHttpContract()
            throws Exception {
        String email = "coupon-signup-" + UUID.randomUUID() + "@example.com";
        mvc.perform(
                        post("/api/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"email":"%s","password":"test1234","name":"쿠폰 테스트"}
                                        """
                                                .formatted(email)))
                .andExpect(status().isCreated());
        long member =
                jdbc.queryForObject("SELECT id FROM members WHERE email = ?", Long.class, email);
        members.add(member);
        awaitGrant(member);
        var first = coupons.list(member);
        assertThat(first).extracting(CouponApi.CouponView::couponId).containsExactly(1L, 2L, 3L);
        assertThat(first)
                .extracting(CouponApi.CouponView::discountType)
                .containsExactly("FIXED_AMOUNT", "PERCENTAGE", "FREE_SHIPPING");
        assertThat(first)
                .extracting(CouponApi.CouponView::discountValue)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("3000"), new BigDecimal("10"), BigDecimal.ZERO);
        assertThat(first)
                .extracting(CouponApi.CouponView::minOrderAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(
                        new BigDecimal("10000"), new BigDecimal("30000"), new BigDecimal("15000"));
        assertThat(first)
                .allSatisfy(
                        coupon -> {
                            assertThat(coupon.status()).isEqualTo("ISSUED");
                            assertThat(coupon.issuedAt()).isEqualTo(NOW);
                            assertThat(coupon.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
                        });
        clock.now = NOW.plus(Duration.ofDays(1));
        assertThat(coupons.list(member)).isEqualTo(first);
        mvc.perform(
                        get("/api/me/coupons")
                                .with(jwt().jwt(jwt -> jwt.subject(Long.toString(member)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].couponName").value("신규 회원 3000원 쿠폰"))
                .andExpect(jsonPath("$.data[0].issuedAt").value(NOW.toString()))
                .andExpect(
                        jsonPath("$.data[0].expiresAt")
                                .value(NOW.plus(Duration.ofDays(90)).toString()));
    }

    @Test
    void concurrentRegistrationDeliveriesCannotIssueDuplicateMemberCoupons() throws Exception {
        long member = member(false);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Void> deliver =
                    () -> {
                        start.await();
                        transactions.executeWithoutResult(
                                status -> events.publishEvent(new MemberRegistered(member)));
                        return null;
                    };
            var first = executor.submit(deliver);
            var second = executor.submit(deliver);
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        awaitGrant(member);
        var original = coupons.list(member);
        clock.now = NOW.plus(Duration.ofDays(1));
        transactions.executeWithoutResult(
                status -> events.publishEvent(new MemberRegistered(member)));
        awaitGrant(member);
        assertThat(coupons.list(member)).isEqualTo(original);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM member_coupon WHERE member_id = ?",
                                Long.class,
                                member))
                .isEqualTo(3);
    }

    @Test
    void rolledBackRegistrationNeverIssuesAndReadPathsNeverCreateCoupons() {
        long member = member(false);
        transactions.executeWithoutResult(
                status -> {
                    events.publishEvent(new MemberRegistered(member));
                    assertThat(coupons.list(member)).isEmpty();
                    status.setRollbackOnly();
                });
        var readOnly =
                new TransactionTemplate(
                        Objects.requireNonNull(transactions.getTransactionManager()));
        readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(
                status -> {
                    assertThat(coupons.list(member)).isEmpty();
                    assertThat(coupons.usableCount(member)).isZero();
                });
        assertInvalid(() -> coupons.reserve(member, Long.MAX_VALUE, 100, new BigDecimal("10000")));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM member_coupon WHERE member_id = ?",
                                Long.class,
                                member))
                .isZero();
        assertThat(outstandingRegistrations()).isZero();
    }

    @Test
    void backfillGrantsExistingMembersWithoutReplacingUsedCouponsOrTheirDates() {
        long member = member(false);
        var issuedAt = NOW.minus(Duration.ofDays(100));
        var expiresAt = NOW.minus(Duration.ofDays(10));
        jdbc.update(
                """
                INSERT INTO member_coupon(member_id,coupon_id,status,issued_at,expires_at)
                VALUES (?,1,'USED',?,?)
                """,
                member,
                Timestamp.from(issuedAt),
                Timestamp.from(expiresAt));
        var migration =
                new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V13__welcome_coupons.sql"));
        migration.execute(Objects.requireNonNull(jdbc.getDataSource()));
        var first = coupons.list(member);
        migration.execute(Objects.requireNonNull(jdbc.getDataSource()));
        assertThat(coupons.list(member)).isEqualTo(first).hasSize(3);
        assertThat(first.getFirst().status()).isEqualTo("USED");
        assertThat(first.getFirst().issuedAt()).isEqualTo(issuedAt);
        assertThat(first.getFirst().expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void concurrentReservationsAllowOnlyOnePendingOrderWithoutExposingAReservedStatus()
            throws Exception {
        long member = member();
        long coupon = coupons.list(member).getFirst().id();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> reserveOutcome(start, member, coupon, 100));
            var second = executor.submit(() -> reserveOutcome(start, member, coupon, 101));
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("RESERVED", "COUPON_INVALID");
        }
        assertThat(coupons.list(member).getFirst().status()).isEqualTo("ISSUED");
        assertThat(coupons.usableCount(member)).isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reserved_order_id FROM member_coupon WHERE id = ?",
                                Long.class,
                                coupon))
                .isIn(100L, 101L);
    }

    @Test
    void ownerOrderReleaseAndRedeemConditionsSurviveRetries() {
        long member = member();
        long other = member();
        long coupon = coupons.list(member).getFirst().id();
        assertInvalid(() -> coupons.reserve(other, coupon, 100, new BigDecimal("10000")));
        assertInvalid(() -> coupons.reserve(member, coupon, 100, new BigDecimal("9999")));
        coupons.reserve(member, coupon, 100, new BigDecimal("10000"));
        coupons.release(other, coupon, 100);
        coupons.release(member, coupon, 101);
        assertInvalid(() -> coupons.reserve(member, coupon, 101, new BigDecimal("10000")));
        assertInvalid(() -> coupons.redeem(other, coupon, 100));
        assertInvalid(() -> coupons.redeem(member, coupon, 101));
        coupons.release(member, coupon, 100);
        coupons.release(member, coupon, 100);
        coupons.reserve(member, coupon, 101, new BigDecimal("10000"));
        coupons.redeem(member, coupon, 101);
        coupons.redeem(member, coupon, 101);
        coupons.release(member, coupon, 101);
        assertThat(coupons.list(member).getFirst().status()).isEqualTo("USED");
        assertThat(coupons.usableCount(member)).isEqualTo(2);
        assertInvalid(() -> coupons.reserve(member, coupon, 102, new BigDecimal("10000")));
    }

    @Test
    void exactNinetyDayExpiryPreventsReservationButIssuedCountKeepsTheMockContract() {
        long member = member();
        long coupon = coupons.list(member).getFirst().id();
        clock.now = NOW.plus(Duration.ofDays(90));
        assertInvalid(() -> coupons.reserve(member, coupon, 100, new BigDecimal("10000")));
        assertThat(coupons.usableCount(member)).isEqualTo(3);
        assertThat(coupons.list(member).getFirst().expiresAt()).isEqualTo(clock.now);
    }

    @Test
    void percentageAndShippingReservationsReturnTheirSeparatePricingEffects() {
        long member = member();
        var issued = coupons.list(member);
        var percent = coupons.reserve(member, issued.get(1).id(), 100, new BigDecimal("30005"));
        assertThat(percent.amount()).isEqualByComparingTo("3000.5");
        assertThat(percent.freeShipping()).isFalse();
        var shipping = coupons.reserve(member, issued.get(2).id(), 101, new BigDecimal("15000"));
        assertThat(shipping.amount()).isZero();
        assertThat(shipping.freeShipping()).isTrue();
    }

    private String reserveOutcome(CountDownLatch start, long member, long coupon, long order)
            throws InterruptedException {
        start.await();
        try {
            coupons.reserve(member, coupon, order, new BigDecimal("10000"));
            return "RESERVED";
        } catch (ApiException error) {
            assertThat(error.status()).isEqualTo(400);
            return error.code();
        }
    }

    private long member() {
        return member(true);
    }

    private long member(boolean registered) {
        Long id =
                jdbc.queryForObject(
                        """
                        INSERT INTO members (email, password_hash, name, created_at) VALUES (?, 'unused', '쿠폰 테스트', ?)
                        RETURNING id
                        """,
                        Long.class,
                        "coupon-" + UUID.randomUUID() + "@example.com",
                        Timestamp.from(NOW));
        members.add(id);
        if (registered) {
            transactions.executeWithoutResult(
                    status -> events.publishEvent(new MemberRegistered(id)));
            awaitGrant(id);
        }
        return id;
    }

    private void awaitGrant(long memberId) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            assertThat(coupons.list(memberId)).hasSize(3);
                            assertThat(outstandingRegistrations()).isZero();
                        });
    }

    private long outstandingRegistrations() {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM event_publication
                WHERE event_type = ? AND completion_date IS NULL
                """,
                Long.class,
                MemberRegistered.class.getName());
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COUPON_INVALID"));
    }
}
