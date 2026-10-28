package io.github.cskwork.lunepick.shared.internal.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SpaController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(SpaConfiguration.class)
class SpaControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void deepLinkServesTheReactEntryAndMissingAssetsRemain404() throws Exception {
        mvc.perform(get("/app/products/3"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("id=\"root\"")));
        mvc.perform(get("/app/orders/history")).andExpect(status().isOk());
        mvc.perform(get("/app/assets/missing.js")).andExpect(status().isNotFound());
        mvc.perform(get("/app/missing.css")).andExpect(status().isNotFound());
        for (String path : new String[] {"/app", "/app/"})
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(content().string(Matchers.containsString("id=\"root\"")));
    }
}
