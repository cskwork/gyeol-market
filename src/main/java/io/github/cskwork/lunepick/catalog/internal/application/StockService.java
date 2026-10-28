package io.github.cskwork.lunepick.catalog.internal.application;

import io.github.cskwork.lunepick.catalog.CatalogStockApi;
import io.github.cskwork.lunepick.catalog.ProductChanged;
import io.github.cskwork.lunepick.shared.ApiException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockService implements CatalogStockApi {
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public StockService(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OptionSnapshot> option(long optionId) {
        return options(List.of(optionId)).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OptionSnapshot> options(Collection<Long> optionIds) {
        if (optionIds.isEmpty()) return List.of();
        return jdbc.sql(
                        """
                        SELECT o.id AS option_id, p.id AS product_id, p.name AS product_name,
                               o.name AS option_name, p.sale_price + o.price AS sale_price,
                               CASE WHEN p.status = 'ON_SALE' THEN o.status ELSE p.status END AS status,
                               o.available_quantity
                          FROM product_option o JOIN product p ON p.id = o.product_id
                         WHERE o.id IN (:ids)
                        """)
                .param("ids", optionIds)
                .query(OptionSnapshot.class)
                .list();
    }

    @Override
    @Transactional
    public void reserve(List<Reservation> reservations) {
        change(reservations, true);
    }

    @Override
    @Transactional
    public void release(List<Reservation> reservations) {
        change(reservations, false);
    }

    private void change(List<Reservation> reservations, boolean reserve) {
        Map<Long, Integer> quantities = new TreeMap<>();
        for (Reservation item : reservations) {
            if (item.quantity() <= 0)
                throw new ApiException(400, "VALIDATION_FAILED", "수량은 1개 이상이어야 합니다.");
            quantities.merge(item.optionId(), item.quantity(), Math::addExact);
        }
        Set<Long> changedProducts = new LinkedHashSet<>();
        for (var entry : quantities.entrySet()) {
            StockRow row = stockRow(entry.getKey(), false);
            if (reserve && !row.onSale()) throw insufficient();
            if (row.availableQuantity() == null) {
                if (reserve) {
                    // Keep status changes behind this reservation without rewriting unlimited
                    // stock.
                    // If inventory became finite while waiting, fail rather than upgrade a shared
                    // lock held by multiple reservers and risk a deadlock.
                    row = unlimitedReservationRow(entry.getKey());
                    if (!row.onSale()) throw insufficient();
                }
                continue;
            }
            row = stockRow(entry.getKey(), true);
            if (reserve
                    && (!row.onSale()
                            || row.availableQuantity() != null
                                    && row.availableQuantity() < entry.getValue()))
                throw insufficient();
            if (row.availableQuantity() == null) continue;
            boolean before = row.inStock();
            long quantity =
                    reserve
                            ? row.availableQuantity() - entry.getValue()
                            : Math.addExact(row.availableQuantity(), entry.getValue());
            jdbc.sql("UPDATE product_option SET available_quantity=:quantity WHERE id=:id")
                    .param("quantity", quantity)
                    .param("id", entry.getKey())
                    .update();
            boolean after = row.onSale() && quantity > 0;
            if (before != after) changedProducts.add(row.productId());
        }
        changedProducts.forEach(id -> events.publishEvent(new ProductChanged(id)));
    }

    private StockRow stockRow(long optionId, boolean lock) {
        return jdbc.sql(
                        """
                SELECT o.product_id, o.available_quantity,
                       (o.status='ON_SALE' AND p.status='ON_SALE') AS on_sale
                FROM product_option o JOIN product p ON p.id=o.product_id
                WHERE o.id=:id
                """
                                + (lock ? " FOR UPDATE OF o FOR SHARE OF p" : ""))
                .param("id", optionId)
                .query(StockRow.class)
                .optional()
                .orElseThrow(this::insufficient);
    }

    private StockRow unlimitedReservationRow(long optionId) {
        return jdbc.sql(
                        """
                SELECT o.product_id, o.available_quantity,
                       (o.status='ON_SALE' AND p.status='ON_SALE') AS on_sale
                FROM product_option o JOIN product p ON p.id=o.product_id
                WHERE o.id=:id AND o.available_quantity IS NULL
                FOR SHARE OF o,p
                """)
                .param("id", optionId)
                .query(StockRow.class)
                .optional()
                .orElseThrow(this::insufficient);
    }

    private ApiException insufficient() {
        return new ApiException(409, "INSUFFICIENT_STOCK", "상품 재고가 부족합니다.");
    }

    private record StockRow(long productId, Long availableQuantity, boolean onSale) {
        boolean inStock() {
            return onSale && (availableQuantity == null || availableQuantity > 0);
        }
    }
}
