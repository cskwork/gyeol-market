package io.github.cskwork.lunepick.cart;

import java.util.List;

/** Cart ownership and checkout cleanup, without exposing persistence. */
public interface CartApi {
    List<Long> selectedItemIds(long memberId, List<Long> optionIds);

    void clearPurchased(long memberId, List<Long> cartItemIds);
}
