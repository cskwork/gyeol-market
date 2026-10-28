package io.github.cskwork.lunepick.address.internal.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cskwork.lunepick.address.AddressApi.Address;
import io.github.cskwork.lunepick.address.internal.application.*;
import io.github.cskwork.lunepick.shared.internal.web.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AddressController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class AddressControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AddressService addresses;
    @MockitoBean JwtDecoder decoder;

    @Test
    void addressesRequireAuthenticationAndListOnlyCurrentMember() throws Exception {
        mvc.perform(get("/api/me/addresses")).andExpect(status().isUnauthorized());
        when(addresses.list(7)).thenReturn(List.of());
        mvc.perform(get("/api/me/addresses").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        verify(addresses).list(7);
    }

    @Test
    void createNormalizesBeforeValidationAndReturns201() throws Exception {
        var normalized = new CreateAddress("수령인", "010-1234-5678", "01234", "서울", null, false);
        when(addresses.create(7, normalized))
                .thenReturn(new Address(9, "수령인", "010-1234-5678", "01234", "서울", null, true));
        mvc.perform(
                        post("/api/me/addresses")
                                .with(jwt().jwt(j -> j.subject("7")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"recipientName":" 수령인 ","phone":" 01012345678 ","zipcode":" 01234 ","addressMain":" 서울 ","addressDetail":" ","isDefault":false}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.isDefault").value(true))
                .andExpect(jsonPath("$.data.phone").value("010-1234-5678"));
        verify(addresses).create(7, normalized);
    }

    @Test
    void requiredLengthAndMobileFormatValidationRejectInvalidAddresses() throws Exception {
        mvc.perform(
                        post("/api/me/addresses")
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(
                        post("/api/me/addresses")
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"recipientName":"수령인","phone":"02-123-4567","zipcode":"01234","addressMain":"서울","isDefault":false}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("phone"))
                .andExpect(
                        jsonPath("$.error.fieldErrors[0].message")
                                .value("연락처를 010-1234-5678 형식으로 입력해주세요."));
        mvc.perform(
                        post("/api/me/addresses")
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"recipientName\":\""
                                                + "가".repeat(101)
                                                + "\",\"phone\":\"01012345678\",\"zipcode\":\"12345\",\"addressMain\":\"주소\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(addresses);
    }
}
