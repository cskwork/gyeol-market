package io.github.cskwork.lunepick.wishlist.internal.web;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cskwork.lunepick.shared.*;
import io.github.cskwork.lunepick.shared.internal.web.*;
import io.github.cskwork.lunepick.wishlist.internal.application.WishlistService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WishlistController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class WishlistControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean WishlistService wishlist;
    @MockitoBean JwtDecoder decoder;

    @Test
    void listRequiresAuthenticationAndUsesPagingEnvelope() throws Exception {
        mvc.perform(get("/api/me/wishlist")).andExpect(status().isUnauthorized());
        when(wishlist.list(7, PageQuery.from("1", "2", 20)))
                .thenReturn(new WishlistService.Page(List.of(), new PageMeta(1, 2, 4)));
        mvc.perform(get("/api/me/wishlist?page=1&size=2").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.size").value(2))
                .andExpect(jsonPath("$.meta.total").value(4));
    }

    @Test
    void addAndDeleteAre200WithNullDataOmitted() throws Exception {
        mvc.perform(
                        post("/api/me/wishlist")
                                .with(jwt().jwt(j -> j.subject("7")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productId\":1}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true}"))
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(delete("/api/me/wishlist/1").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(wishlist).add(7, 1);
        verify(wishlist).remove(7, 1);
    }

    @Test
    void addValidatesProductIdAndKeepsMissingProductCode() throws Exception {
        mvc.perform(
                        post("/api/me/wishlist")
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        doThrow(new ApiException(404, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."))
                .when(wishlist)
                .add(7, 999);
        mvc.perform(
                        post("/api/me/wishlist")
                                .with(jwt().jwt(j -> j.subject("7")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productId\":999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }
}
