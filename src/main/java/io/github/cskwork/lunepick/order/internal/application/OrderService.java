package io.github.cskwork.lunepick.order.internal.application;

import io.github.cskwork.lunepick.address.AddressApi;
import io.github.cskwork.lunepick.cart.CartApi;
import io.github.cskwork.lunepick.catalog.CatalogStockApi;
import io.github.cskwork.lunepick.catalog.CatalogStockApi.Reservation;
import io.github.cskwork.lunepick.coupon.CouponApi;
import io.github.cskwork.lunepick.member.MemberApi;
import io.github.cskwork.lunepick.order.OrderApi;
import io.github.cskwork.lunepick.order.internal.application.OrderDtos.*;
import io.github.cskwork.lunepick.order.internal.config.OrderExpiryRetryProperties;
import io.github.cskwork.lunepick.order.internal.domain.OrderPricing;
import io.github.cskwork.lunepick.order.internal.persistence.OrderStore;
import io.github.cskwork.lunepick.order.internal.persistence.OrderStore.Row;
import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.PageMeta;
import io.github.cskwork.lunepick.shared.PageQuery;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService implements OrderApi {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final OrderStore store;
    private final MemberApi members;
    private final CatalogStockApi stock;
    private final CouponApi coupons;
    private final AddressApi addresses;
    private final CartApi carts;
    private final Clock clock;
    private final TransactionTemplate expiryTransaction;
    private final OrderExpiryRetryProperties expiryRetry;

    public OrderService(
            OrderStore store,
            MemberApi members,
            CatalogStockApi stock,
            CouponApi coupons,
            AddressApi addresses,
            CartApi carts,
            Clock clock,
            PlatformTransactionManager transactionManager,
            OrderExpiryRetryProperties expiryRetry) {
        this.store = store;
        this.members = members;
        this.stock = stock;
        this.coupons = coupons;
        this.addresses = addresses;
        this.carts = carts;
        this.clock = clock;
        this.expiryRetry = expiryRetry;
        this.expiryTransaction = new TransactionTemplate(transactionManager);
        expiryTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public CreateResponse create(long memberId, String key, CreateRequest request) {
        if (key == null || key.isBlank())
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key 헤더가 필요합니다.");
        if (key.length() > 200)
            throw new ApiException(400, "VALIDATION_FAILED", "Idempotency-Key는 200자 이하여야 합니다.");
        Validated body = validate(request);
        String canonical = canonical(body);
        var attempt = store.insertAttempt(memberId, key, canonical, clock.instant());
        if (attempt.isEmpty()) {
            Row existing = store.byKey(memberId, key);
            if (!existing.canonical().equals(canonical))
                throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "같은 키에 다른 주문 요청을 사용할 수 없습니다.");
            return existing.created();
        }
        Row row = attempt.orElseThrow();
        var address = addresses.owned(memberId, body.addressId());
        // All checkout paths acquire member points, sorted stock, then coupon locks in that order.
        members.reservePoints(memberId, body.points());
        List<Reservation> reservations = reservations(body.items());
        var snapshots =
                body.items().stream()
                        .map(
                                item ->
                                        stock.option(item.optionId())
                                                .orElseThrow(
                                                        () ->
                                                                new ApiException(
                                                                        404,
                                                                        "PRODUCT_NOT_FOUND",
                                                                        "상품을 찾을 수 없습니다.")))
                        .toList();
        stock.reserve(reservations);
        BigDecimal subtotal = BigDecimal.ZERO;
        for (int i = 0; i < body.items().size(); i++)
            subtotal =
                    subtotal.add(
                            snapshots
                                    .get(i)
                                    .salePrice()
                                    .multiply(BigDecimal.valueOf(body.items().get(i).quantity())));
        var discount =
                body.couponId() == null
                        ? new CouponApi.Discount(BigDecimal.ZERO, false)
                        : coupons.reserve(memberId, body.couponId(), row.id(), subtotal);
        OrderPricing pricing =
                OrderPricing.calculate(
                        subtotal, discount.amount(), discount.freeShipping(), body.points());
        String no = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + row.id();
        long paymentKey = store.nextPaymentKey();
        store.finish(row.id(), no, paymentKey, pricing, body.couponId(), address);
        for (int i = 0; i < body.items().size(); i++)
            store.addItem(row.id(), i, snapshots.get(i), body.items().get(i).quantity());
        store.saveCartItems(
                row.id(),
                carts.selectedItemIds(
                        memberId, body.items().stream().map(Line::optionId).toList()));
        return new CreateResponse(no, paymentKey, pricing.total(), null);
    }

    @Transactional(readOnly = true)
    public Detail detail(long memberId, String orderNo) {
        return store.detail(owned(memberId, store.byOrderNo(orderNo, false)));
    }

    @Transactional(readOnly = true)
    public OrderPage list(long memberId, String status, PageQuery page) {
        String filter = status == null || status.isEmpty() ? null : status;
        return new OrderPage(
                store.list(memberId, filter, page),
                new PageMeta(page.page(), page.size(), store.count(memberId, filter)));
    }

    @Transactional(readOnly = true)
    public Summary summary(long memberId) {
        return new Summary(
                members.availablePoints(memberId),
                coupons.usableCount(memberId),
                store.count(memberId, "PAID"),
                "BRONZE",
                members.findMember(memberId)
                        .orElseThrow(() -> new ApiException(401, "UNAUTHORIZED", "로그인이 필요합니다."))
                        .name());
    }

    @Override
    @Transactional
    public PaymentTarget paymentTarget(String orderNo) {
        Row row = store.byOrderNo(orderNo, true);
        return new PaymentTarget(
                row.id(),
                row.orderNo(),
                row.memberId(),
                Long.toString(row.paymentKey()),
                row.amount(),
                row.status());
    }

    @Override
    @Transactional
    public PaymentResult confirm(
            long memberId, String orderNo, String paymentKey, BigDecimal amount) {
        Row row = owned(memberId, store.byOrderNo(orderNo, true));
        OrderPricing.requirePayment(
                Long.toString(row.paymentKey()), row.amount(), paymentKey, amount);
        if ("PAID".equals(row.status())) return result(row, "PAID");
        OrderPricing.requirePending(row.status());
        members.settlePoints(memberId, row.points());
        if (row.couponId() != null) coupons.redeem(memberId, row.couponId(), row.id());
        carts.clearPurchased(memberId, store.cartItems(row.id()));
        store.status(row.id(), "PAID");
        return result(row, "PAID");
    }

    @Override
    @Transactional
    public PaymentResult fail(String orderNo, String paymentKey, BigDecimal amount, String state) {
        if (!"FAILED".equals(state) && !"CANCELLED".equals(state))
            throw new ApiException(400, "VALIDATION_FAILED", "결제 실패 상태가 올바르지 않습니다.");
        Row row = store.byOrderNo(orderNo, true);
        OrderPricing.requirePayment(
                Long.toString(row.paymentKey()), row.amount(), paymentKey, amount);
        if (row.status().equals(state)) return result(row, state);
        OrderPricing.requirePending(row.status());
        release(row, state);
        return result(row, state);
    }

    @Override
    @Transactional
    public void holdForReconciliation(long orderId, String reason) {
        store.holdForReconciliation(orderId, reason, clock.instant());
    }

    public int expirePending(Duration ttl, int batchSize) {
        int expired = 0;
        for (int attempted = 0; attempted < batchSize; attempted++) {
            AtomicLong candidate = new AtomicLong();
            try {
                ExpiryOutcome outcome =
                        expiryTransaction.execute(
                                status -> {
                                    Instant now = clock.instant();
                                    var rows = store.expiredPending(now.minus(ttl), now, 1);
                                    if (rows.isEmpty()) return ExpiryOutcome.EMPTY;
                                    Row row = rows.getFirst();
                                    candidate.set(row.id());
                                    // A second statement gets a fresh READ COMMITTED snapshot after
                                    // the row lock.
                                    // A provider timeout can commit its independent hold while this
                                    // claim starts.
                                    if (store.reconciliationHeld(row.id()))
                                        return ExpiryOutcome.HELD;
                                    release(row, "CANCELLED");
                                    return ExpiryOutcome.EXPIRED;
                                });
                if (outcome == ExpiryOutcome.EMPTY) break;
                if (outcome == ExpiryOutcome.EXPIRED) expired++;
            } catch (RuntimeException failure) {
                if (candidate.get() == 0) {
                    log.error("Cannot claim checkout expiry", failure);
                    break;
                }
                try {
                    var recorded =
                            expiryTransaction.execute(
                                    status ->
                                            store.recordExpiryFailure(
                                                    candidate.get(),
                                                    clock.instant(),
                                                    failure.getClass().getSimpleName(),
                                                    expiryRetry));
                    recorded.ifPresent(
                            retry ->
                                    log.warn(
                                            "Checkout expiry failed: orderId={} attempt={} nextAttemptAt={}",
                                            candidate.get(),
                                            retry.attempts(),
                                            retry.nextAttemptAt(),
                                            failure));
                    if (recorded.isEmpty())
                        log.warn(
                                "Checkout expiry transaction failed after terminal transition: orderId={}",
                                candidate.get(),
                                failure);
                } catch (RuntimeException recordingFailure) {
                    log.error(
                            "Cannot record checkout expiry failure: orderId={}",
                            candidate.get(),
                            recordingFailure);
                    break;
                }
            }
        }
        return expired;
    }

    private enum ExpiryOutcome {
        EMPTY,
        HELD,
        EXPIRED
    }

    @Transactional
    public int forgetExpiredReplayKeys(Duration retention) {
        return store.forgetExpiredReplayKeys(clock.instant().minus(retention));
    }

    private void release(Row row, String state) {
        members.releasePoints(row.memberId(), row.points());
        stock.release(
                store.stockLines(row.id()).stream()
                        .map(line -> new Reservation(line.optionId(), line.quantity()))
                        .toList());
        if (row.couponId() != null) coupons.release(row.memberId(), row.couponId(), row.id());
        store.status(row.id(), state);
    }

    private Row owned(long memberId, Row row) {
        if (row.memberId() != memberId)
            throw new ApiException(404, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.");
        return row;
    }

    private PaymentResult result(Row row, String status) {
        return new PaymentResult(
                row.id(), row.orderNo(), row.memberId(), status, Long.toString(row.paymentKey()));
    }

    private Validated validate(CreateRequest body) {
        if (body == null || body.items() == null || body.items().isEmpty())
            throw new ApiException(400, "VALIDATION_FAILED", "주문 상품이 없습니다.");
        if (body.deliveryAddressId() == null || body.deliveryAddressId() < 1)
            throw new ApiException(404, "ADDRESS_NOT_FOUND", "배송지를 찾을 수 없습니다.");
        List<Line> lines =
                body.items().stream()
                        .map(
                                item -> {
                                    if (item == null
                                            || item.productOptionId() == null
                                            || item.productOptionId() < 1)
                                        throw new ApiException(
                                                404, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다.");
                                    return new Line(
                                            item.productOptionId(),
                                            OrderPricing.quantity(item.quantity()));
                                })
                        .toList();
        BigDecimal points = body.usePointAmount() == null ? BigDecimal.ZERO : body.usePointAmount();
        OrderPricing.validatePoints(points);
        return new Validated(
                lines, body.couponId(), points.stripTrailingZeros(), body.deliveryAddressId());
    }

    private List<Reservation> reservations(List<Line> lines) {
        Map<Long, Integer> quantities = new TreeMap<>();
        for (Line line : lines) quantities.merge(line.optionId(), line.quantity(), Math::addExact);
        return quantities.entrySet().stream()
                .map(e -> new Reservation(e.getKey(), e.getValue()))
                .toList();
    }

    private String canonical(Validated body) {
        StringBuilder key =
                new StringBuilder()
                        .append(body.addressId())
                        .append('|')
                        .append(body.couponId())
                        .append('|')
                        .append(body.points().toPlainString());
        for (Line line : body.items())
            key.append('|').append(line.optionId()).append(':').append(line.quantity());
        return key.toString();
    }

    private record Line(long optionId, int quantity) {}

    private record Validated(List<Line> items, Long couponId, BigDecimal points, long addressId) {}

    public record OrderPage(List<ListItem> items, PageMeta meta) {}
}
