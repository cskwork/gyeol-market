package io.github.cskwork.lunepick.member.internal.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cskwork.lunepick.member.internal.application.AuthDtos;
import io.github.cskwork.lunepick.member.internal.application.AuthService;
import io.github.cskwork.lunepick.member.internal.application.LoginFailureGuard;
import io.github.cskwork.lunepick.member.internal.config.MemberWebPolicy;
import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.internal.web.ApiExceptionHandler;
import io.github.cskwork.lunepick.shared.internal.web.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class, MemberWebPolicy.class})
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean LoginFailureGuard failures;
    @MockitoBean JwtDecoder decoder;

    @Test
    void signupReturns201MemberIdAndNormalizesEmail() throws Exception {
        when(auth.signup(new AuthDtos.SignupRequest("user@example.com", "abcdefgh", "회원", null)))
                .thenReturn(new AuthDtos.SignupResponse(7));
        mvc.perform(
                        post("/api/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"email":" USER@Example.com ","password":"abcdefgh","name":"회원"}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.memberId").value(7))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void signupLengthValidationReturns400WithoutEchoingPassword() throws Exception {
        mvc.perform(
                        post("/api/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"email":"user@example.com","password":"short","name":"회원"}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("password"))
                .andExpect(jsonPath("$.error.fieldErrors[0].rejectedValue").doesNotExist());
        verifyNoInteractions(auth);
    }

    @Test
    void signupRejectsMalformedEmailBlankNameAndInvalidOptionalPhone() throws Exception {
        mvc.perform(
                        post("/api/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"email":"not-email","password":"abcdefgh","name":" ","phone":"123"}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors.length()").value(3));
        verifyNoInteractions(auth);
    }

    @Test
    void duplicateEmailKeeps409AndFrontendErrorCode() throws Exception {
        when(auth.signup(any()))
                .thenThrow(new ApiException(409, "EMAIL_DUPLICATED", "이미 가입된 이메일입니다."));
        mvc.perform(
                        post("/api/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"email":"user@example.com","password":"abcdefgh","name":"회원"}
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_DUPLICATED"));
    }

    @Test
    void loginReturnsFrontendTokenContract() throws Exception {
        when(auth.login(any())).thenReturn(new AuthDtos.TokenResponse("access", "refresh", 1800));
        mvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"email":"user@example.com","password":"abcdefgh"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh"))
                .andExpect(jsonPath("$.data.expiresInSec").value(1800));
    }

    @Test
    void badLoginReturns401InvalidCredentialsEnvelope() throws Exception {
        when(auth.login(any()))
                .thenThrow(new ApiException(401, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."));
        mvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"email":"user@example.com","password":"bad"}
                                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        verify(failures).recordFailure("user@example.com", "127.0.0.1");
    }

    @Test
    void sharedEmailCooldownReturnsRetryAfterBeforePasswordHashing() throws Exception {
        when(failures.retryAfter("user@example.com")).thenReturn(30L);
        mvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"user@example.com\",\"password\":\"abcdefgh\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        verifyNoInteractions(auth);
    }

    @Test
    void refreshReturnsTokensAndInvalidRefreshKeeps401Envelope() throws Exception {
        when(auth.refresh(new AuthDtos.RefreshRequest("valid")))
                .thenReturn(new AuthDtos.TokenResponse("new", "rotated", 1800));
        when(auth.refresh(new AuthDtos.RefreshRequest("replayed")))
                .thenThrow(new ApiException(401, "INVALID_TOKEN", "세션이 만료되었습니다."));
        mvc.perform(
                        post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"valid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshToken").value("rotated"));
        mvc.perform(
                        post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"replayed\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    @Test
    void refreshRequiresNonBlankToken() throws Exception {
        mvc.perform(
                        post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(auth);
    }

    @Test
    void logoutRequiresBearerAuthenticationAndOmitsNullData() throws Exception {
        mvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(
                        post("/api/auth/logout")
                                .with(jwt().jwt(jwt -> jwt.subject("7").claim("sid", "family"))))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true}"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(auth).logout(any(Jwt.class));
    }

    @Test
    void invalidBearerTokenReturns401JsonInsteadOfLoginRedirect() throws Exception {
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("invalid"));
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(auth);
    }
}
