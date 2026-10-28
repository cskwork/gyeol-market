package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.PageQuery;
import io.github.cskwork.lunepick.wishlist.WishlistApi;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "lunepick.wishlist.max-entries=2")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WishlistBoundsIntegrationTest extends EmbeddedDatabase {
    @Autowired WishlistApi wishlist;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    void capAllowsDuplicatesAndRemovalAndReturnsTheDisplayableError() throws Exception {
        wishlist.add(901, 1);
        wishlist.add(901, 2);
        wishlist.add(901, 1);
        mvc.perform(
                        post("/api/me/wishlist")
                                .with(jwt().jwt(token -> token.subject("901")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productId\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WISHLIST_LIMIT_REACHED"))
                .andExpect(jsonPath("$.error.message").value("위시리스트는 최대 2개까지 담을 수 있습니다."));
        assertThat(wishlist.list(901, new PageQuery(0, 20)).meta().total()).isEqualTo(2);
        wishlist.remove(901, 1);
        wishlist.add(901, 3);
        assertThat(wishlist.list(901, new PageQuery(0, 20)).items()).hasSize(2);
    }

    @Test
    void concurrentDistinctAdditionsCannotExceedTheMemberCap() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            ArrayList<Future<Boolean>> results = new ArrayList<>();
            for (long product = 1; product <= 8; product++) {
                long id = product;
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        wishlist.add(902, id);
                                        return true;
                                    } catch (ApiException error) {
                                        assertThat(error.code())
                                                .isEqualTo("WISHLIST_LIMIT_REACHED");
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            int added = 0;
            for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) added++;
            assertThat(added).isEqualTo(2);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM wishlist_items WHERE member_id=902",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void legacyOverflowStillHasBoundedReadWorkAndCannotGrowFurther() {
        for (long product = 1; product <= 8; product++)
            jdbc.update("INSERT INTO wishlist_items(member_id,product_id) VALUES(903,?)", product);
        assertThat(wishlist.list(903, new PageQuery(0, 20)).items()).hasSize(2);
        assertThatThrownBy(() -> wishlist.add(903, 9)).isInstanceOf(ApiException.class);
    }
}
