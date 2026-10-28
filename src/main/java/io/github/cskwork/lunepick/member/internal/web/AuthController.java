package io.github.cskwork.lunepick.member.internal.web;

import io.github.cskwork.lunepick.member.internal.application.AuthDtos;
import io.github.cskwork.lunepick.member.internal.application.AuthService;
import io.github.cskwork.lunepick.member.internal.application.LoginFailureGuard;
import io.github.cskwork.lunepick.shared.ApiException;
import io.github.cskwork.lunepick.shared.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final LoginFailureGuard failures;

    AuthController(AuthService auth, LoginFailureGuard failures) {
        this.auth = auth;
        this.failures = failures;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "회원가입")
    ApiResponse<AuthDtos.SignupResponse> signup(
            @Valid @RequestBody AuthDtos.SignupRequest request) {
        return ApiResponse.ok(auth.signup(request));
    }

    @PostMapping("/login")
    @Operation(summary = "로그인 및 토큰 발급")
    ApiResponse<AuthDtos.TokenResponse> login(
            @Valid @RequestBody AuthDtos.LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        long retry = failures.retryAfter(request.email());
        if (retry > 0) {
            response.setHeader("Retry-After", Long.toString(retry));
            throw new ApiException(429, "RATE_LIMITED", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");
        }
        try {
            return ApiResponse.ok(auth.login(request));
        } catch (ApiException error) {
            if (error.status() == 401 && "INVALID_CREDENTIALS".equals(error.code()))
                failures.recordFailure(request.email(), servletRequest.getRemoteAddr());
            throw error;
        }
    }

    @PostMapping("/refresh")
    @Operation(summary = "refresh 토큰 회전")
    ApiResponse<AuthDtos.TokenResponse> refresh(
            @Valid @RequestBody AuthDtos.RefreshRequest request) {
        return ApiResponse.ok(auth.refresh(request));
    }

    @PostMapping("/logout")
    @Operation(summary = "현재 토큰 가족 로그아웃")
    ApiResponse<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        auth.logout(jwt);
        return ApiResponse.ok(null);
    }
}
