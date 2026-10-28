package io.github.cskwork.lunepick.catalog;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Prices include the option surcharge; reservations join the caller's transaction. */
public interface CatalogStockApi {
    Optional<OptionSnapshot> option(long optionId);

    List<OptionSnapshot> options(Collection<Long> optionIds);

    void reserve(List<Reservation> reservations);

    void release(List<Reservation> reservations);

    record OptionSnapshot(
            long optionId,
            long productId,
            String productName,
            String optionName,
            BigDecimal salePrice,
            String status,
            Long availableQuantity) {}

    record Reservation(long optionId, int quantity) {}
}
