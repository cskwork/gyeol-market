package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cskwork.lunepick.address.AddressApi;
import io.github.cskwork.lunepick.address.internal.application.*;
import io.github.cskwork.lunepick.cart.CartApi;
import io.github.cskwork.lunepick.cart.internal.application.CartService;
import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerCommerceIntegrationTest extends EmbeddedDatabase {
    static final long MEMBER = 900001, OTHER = 900002;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired CartApi cartApi;
    @Autowired CartService cart;
    @Autowired AddressApi addressApi;
    @Autowired AddressService addresses;

    @BeforeEach
    @AfterEach
    void cleanOwnedFixtures() {
        for (String table : List.of("cart_items", "addresses", "wishlist_items")) {
            jdbc.update("delete from " + table + " where member_id in (?,?)", MEMBER, OTHER);
        }
    }

    @Test
    void cartLifecyclePreservesPriceQuantityOwnershipAndNullEnvelope() throws Exception {
        var added =
                body(
                        mvc.perform(
                                        as(MEMBER, post("/api/cart/items"))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"productOptionId\":2,\"quantity\":2}"))
                                .andExpect(status().isCreated())
                                .andReturn());
        long id = added.at("/data/cartItemId").asLong();
        assertThat(added.at("/data/quantity").asInt()).isEqualTo(2);
        var view =
                body(
                        mvc.perform(as(MEMBER, get("/api/cart")))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(view.at("/data/items/0/productOptionId").asLong()).isEqualTo(2);
        assertThat(view.at("/data/items/0/salePrice").decimalValue()).isEqualByComparingTo("25000");
        assertThat(view.at("/data/items/0/lineSubtotal").decimalValue())
                .isEqualByComparingTo("50000");
        assertThat(view.at("/data/totalItemCount").asInt()).isEqualTo(2);
        assertThat(view.at("/data/items/0/onSale").asBoolean()).isTrue();
        assertThat(view.at("/data/items/0/availableQuantity").isNull()).isTrue();
        assertThat(
                        body(mvc.perform(as(OTHER, get("/api/cart"))).andReturn())
                                .at("/data/items")
                                .size())
                .isZero();
        mvc.perform(
                        as(OTHER, patch("/api/cart/items/" + id))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quantity\":3}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CART_ITEM_NOT_FOUND"));
        mvc.perform(as(OTHER, delete("/api/cart/items/" + id))).andExpect(status().isNotFound());
        mvc.perform(
                        as(MEMBER, patch("/api/cart/items/" + id))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true}"))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(cart.view(MEMBER).items().getFirst().quantity()).isEqualTo(3);
        mvc.perform(as(MEMBER, delete("/api/cart/items/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(cart.view(MEMBER).items()).isEmpty();
    }

    @Test
    void cartRejectsFractionalOutOfRangeAndUnknownOptionWithoutWriting() throws Exception {
        for (String qty : List.of("1.5", "0", "100", "9999999999999999999999")) {
            mvc.perform(
                            as(MEMBER, post("/api/cart/items"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"productOptionId\":1,\"quantity\":" + qty + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(
                        as(MEMBER, post("/api/cart/items"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productOptionId\":999999,\"quantity\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_OPTION_NOT_FOUND"));
        long id = cart.add(MEMBER, 1, BigDecimal.ONE).cartItemId();
        mvc.perform(
                        as(MEMBER, patch("/api/cart/items/" + id))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quantity\":2.9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(cart.view(MEMBER).totalItemCount()).isEqualTo(1);
    }

    @Test
    void simultaneousAddsNeverDuplicateOrLoseQuantityAndCapAt99() throws Exception {
        concurrent(12, index -> cart.add(MEMBER, 1, BigDecimal.valueOf(10)));
        var view = cart.view(MEMBER);
        assertThat(view.items()).hasSize(1);
        assertThat(view.totalItemCount()).isEqualTo(99);
    }

    @Test
    void simultaneousAbsoluteQuantityUpdatesKeepOneValidLine() throws Exception {
        long id = cart.add(MEMBER, 1, BigDecimal.ONE).cartItemId();
        concurrent(
                6,
                index -> {
                    cart.update(MEMBER, id, BigDecimal.valueOf(index + 2));
                    return null;
                });
        var items = cart.view(MEMBER).items();
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().quantity()).isBetween(2, 7);
    }

    @Test
    void mergeRemainsAnEmptyIdempotentContractWithoutGuestStorage() throws Exception {
        assertThat(cart.merge(MEMBER, "session").mergedItemCount()).isZero();
        assertThat(cart.merge(MEMBER, "session").mergedItemCount()).isZero();
        mvc.perform(
                        as(MEMBER, post("/api/cart/merge"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sessionId\":\"unknown-session\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mergedItemCount").value(0));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT to_regclass('cart_guest_sessions')::text", String.class))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT to_regclass('cart_guest_items')::text", String.class))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.columns WHERE"
                                        + " table_name='cart_items' AND column_name='version'",
                                Integer.class))
                .isZero();
    }

    @Test
    void checkoutCartSnapshotAndCleanupCannotDeleteAnotherMemberLine() {
        long mine = cart.add(MEMBER, 1, BigDecimal.ONE).cartItemId();
        long other = cart.add(OTHER, 1, BigDecimal.ONE).cartItemId();
        long retained = cart.add(MEMBER, 2, BigDecimal.ONE).cartItemId();
        assertThat(cartApi.selectedItemIds(MEMBER, List.of(1L))).containsExactly(mine);
        cartApi.clearPurchased(MEMBER, List.of(mine, other));
        assertThat(cartApi.selectedItemIds(MEMBER, List.of(1L, 2L))).containsExactly(retained);
        assertThat(cartApi.selectedItemIds(OTHER, List.of(1L))).containsExactly(other);
    }

    @Test
    void addressNormalizationDefaultsAndOwnershipMatchStorefront() throws Exception {
        var response =
                body(
                        mvc.perform(
                                        as(MEMBER, post("/api/me/addresses"))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"recipientName":" 수령인 ","phone":" 01012345678 ","zipcode":" 01234 ","addressMain":" 서울 ","addressDetail":" ","isDefault":false}
                                                        """))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.data.isDefault").value(true))
                                .andReturn());
        long first = response.at("/data/id").asLong();
        assertThat(response.at("/data/recipientName").asText()).isEqualTo("수령인");
        assertThat(response.at("/data/phone").asText()).isEqualTo("010-1234-5678");
        assertThat(response.at("/data/addressDetail").isNull()).isTrue();
        var second = addresses.create(MEMBER, address(false));
        assertThat(second.isDefault()).isFalse();
        var replacement = addresses.create(MEMBER, address(true));
        assertThat(replacement.isDefault()).isTrue();
        assertThat(addressApi.owned(MEMBER, first).isDefault()).isFalse();
        assertThat(addresses.list(MEMBER).stream().filter(AddressApi.Address::isDefault))
                .hasSize(1);
        assertThatThrownBy(() -> addressApi.owned(OTHER, first))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("ADDRESS_NOT_FOUND"));
        mvc.perform(as(OTHER, get("/api/me/addresses")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void concurrentFirstAndExplicitDefaultsMaintainExactlyOneDefault() throws Exception {
        concurrent(6, index -> addresses.create(MEMBER, address(false)));
        assertThat(addresses.list(MEMBER)).hasSize(6);
        assertThat(addresses.list(MEMBER).stream().filter(AddressApi.Address::isDefault))
                .hasSize(1);
        concurrent(6, index -> addresses.create(MEMBER, address(true)));
        assertThat(addresses.list(MEMBER)).hasSize(12);
        assertThat(addresses.list(MEMBER).stream().filter(AddressApi.Address::isDefault))
                .hasSize(1);
    }

    @Test
    void wishlistDedupesPagesNewestFirstAndDeletesOnlyCurrentMember() throws Exception {
        for (long product : List.of(1L, 2L, 3L, 1L)) {
            mvc.perform(
                            as(MEMBER, post("/api/me/wishlist"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"productId\":" + product + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        var page =
                body(
                        mvc.perform(as(MEMBER, get("/api/me/wishlist?page=0&size=2")))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(page.at("/data/0/productId").asLong()).isEqualTo(3);
        assertThat(page.at("/data/1/productId").asLong()).isEqualTo(2);
        assertThat(page.at("/meta/total").asLong()).isEqualTo(3);
        assertThat(page.at("/data/0/productName").asText()).isNotBlank();
        var next = body(mvc.perform(as(MEMBER, get("/api/me/wishlist?page=1&size=2"))).andReturn());
        assertThat(next.at("/data/0/productId").asLong()).isEqualTo(1);
        mvc.perform(as(OTHER, delete("/api/me/wishlist/1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from wishlist_items where member_id=?",
                                Long.class,
                                MEMBER))
                .isEqualTo(3L);
        mvc.perform(as(MEMBER, delete("/api/me/wishlist/1"))).andExpect(status().isOk());
        mvc.perform(as(MEMBER, delete("/api/me/wishlist/1"))).andExpect(status().isOk());
        mvc.perform(
                        as(MEMBER, post("/api/me/wishlist"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productId\":999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void concurrentWishlistAddsRemainIdempotent() throws Exception {
        concurrent(
                8,
                index ->
                        mvc.perform(
                                        as(MEMBER, post("/api/me/wishlist"))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"productId\":1}"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from wishlist_items where member_id=?",
                                Long.class,
                                MEMBER))
                .isEqualTo(1L);
    }

    private CreateAddress address(boolean isDefault) {
        return new CreateAddress("수령인", "01012345678", "01234", "서울", "101호", isDefault);
    }

    private MockHttpServletRequestBuilder as(long memberId, MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(token -> token.subject(Long.toString(memberId))));
    }

    private JsonNode body(MvcResult response) throws Exception {
        return mapper.readTree(response.getResponse().getContentAsString());
    }

    @FunctionalInterface
    interface IndexedCall<T> {
        T call(int index) throws Exception;
    }

    private <T> List<T> concurrent(int count, IndexedCall<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<T>>();
            for (int index = 0; index < count; index++) {
                int captured = index;
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return task.call(captured);
                                }));
            }
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }
}
