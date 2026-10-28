package io.github.cskwork.lunepick.cart.internal.application;

import java.util.List;

public interface CartStore {
    record Line(long id, long optionId, int quantity) {}

    List<Line> lines(long memberId);

    Line add(long memberId, long optionId, int quantity);

    boolean update(long memberId, long itemId, int quantity);

    boolean delete(long memberId, long itemId);
}
