package io.github.cskwork.lunepick.order.internal.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cskwork.lunepick.order.internal.application.OrderDtos.*;
import io.github.cskwork.lunepick.order.internal.application.OrderService;
import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.PageMeta;
import io.github.cskwork.lunepick.shared.PageQuery;
import io.github.cskwork.lunepick.shared.internal.web.SecurityConfiguration;
import java.math.BigDecimal;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
@Import(SecurityConfiguration.class)
class OrderControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OrderService orders;
    @MockitoBean JwtDecoder decoder;

    @Test
    void createRetainsDecimalQuantityForServiceValidationAndNumericPaymentKey() throws Exception {
        when(orders.create(eq(7L), eq("attempt"), any()))
                .thenReturn(new CreateResponse("order", 100001, new BigDecimal("5000"), null));
        mvc.perform(
                        post("/api/orders")
                                .with(jwt().jwt(j -> j.subject("7")))
                                .header("Idempotency-Key", "attempt")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"items\":[{\"productOptionId\":1,\"quantity\":1.5}],\"deliveryAddressId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentKey").value(100001))
                .andExpect(jsonPath("$.data.pgCheckoutPayload").value(Matchers.nullValue()));
        verify(orders)
                .create(
                        7,
                        "attempt",
                        new CreateRequest(
                                List.of(new ItemRequest(1L, new BigDecimal("1.5"))),
                                null,
                                null,
                                2L));
    }

    @Test
    void missingKeyAndForeignOrderKeepDomainCodes() throws Exception {
        when(orders.create(eq(7L), isNull(), any()))
                .thenThrow(new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "header"));
        mvc.perform(
                        post("/api/orders")
                                .with(jwt().jwt(j -> j.subject("7")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        when(orders.detail(7, "foreign"))
                .thenThrow(new ApiException(404, "ORDER_NOT_FOUND", "missing"));
        mvc.perform(get("/api/orders/foreign").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void listAndSummaryUseAuthenticatedMemberAndMockEnvelope() throws Exception {
        when(orders.list(7, "PAID", new PageQuery(0, 10)))
                .thenReturn(new OrderService.OrderPage(List.of(), new PageMeta(0, 10, 0)));
        mvc.perform(get("/api/orders").param("status", "PAID").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.size").value(10));
        when(orders.summary(7))
                .thenReturn(new Summary(new BigDecimal("5000"), 3, 0, "BRONZE", "홍길동"));
        mvc.perform(get("/api/me/summary").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gradeName").value("BRONZE"))
                .andExpect(jsonPath("$.data.name").value("홍길동"));
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }
}
