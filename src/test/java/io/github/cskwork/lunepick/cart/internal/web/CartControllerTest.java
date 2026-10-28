package io.github.cskwork.lunepick.cart.internal.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cskwork.lunepick.cart.internal.application.*;
import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.internal.web.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CartController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class CartControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CartService cart;
    @MockitoBean JwtDecoder decoder;

    @Test
    void cartRequiresAuthenticationAndReturnsTypedTotals() throws Exception {
        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
        when(cart.view(7)).thenReturn(new CartViews.Cart(List.of(), 0, BigDecimal.ZERO));
        mvc.perform(get("/api/cart").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalItemCount").value(0))
                .andExpect(jsonPath("$.data.totalAmount").value(0));
    }

    @Test
    void addReturns201AndDoesNotTruncateFractionalInput() throws Exception {
        when(cart.add(7, 2, new BigDecimal("3"))).thenReturn(new CartViews.Added(9, 3));
        mvc.perform(
                        post("/api/cart/items")
                                .with(jwt().jwt(j -> j.subject("7")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productOptionId\":2,\"quantity\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.cartItemId").value(9))
                .andExpect(jsonPath("$.data.quantity").value(3));
        when(cart.add(7, 2, new BigDecimal("1.5")))
                .thenThrow(new ApiException(400, "VALIDATION_FAILED", "수량은 1~99개까지 가능합니다."));
        mvc.perform(
                        post("/api/cart/items")
                                .with(jwt().jwt(j -> j.subject("7")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productOptionId\":2,\"quantity\":1.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void updateDeleteAndMergePreserveEnvelope() throws Exception {
        mvc.perform(
                        patch("/api/cart/items/9")
                                .with(jwt().jwt(j -> j.subject("7")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true}"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(cart).update(7, 9, new BigDecimal("2"));
        mvc.perform(delete("/api/cart/items/9").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(cart).remove(7, 9);
        when(cart.merge(7, "guest")).thenReturn(new CartViews.Merged(0));
        mvc.perform(
                        post("/api/cart/merge")
                                .with(jwt().jwt(j -> j.subject("7")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sessionId\":\"guest\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mergedItemCount").value(0));
    }

    @Test
    void validationAndOwnershipFailuresKeepErrorCodes() throws Exception {
        mvc.perform(
                        post("/api/cart/items")
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(
                        post("/api/cart/merge")
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sessionId\":\" \"}"))
                .andExpect(status().isBadRequest());
        doThrow(new ApiException(404, "CART_ITEM_NOT_FOUND", "장바구니 상품을 찾을 수 없습니다."))
                .when(cart)
                .remove(7, 9);
        mvc.perform(delete("/api/cart/items/9").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CART_ITEM_NOT_FOUND"));
    }
}
