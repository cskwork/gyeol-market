package io.github.cskwork.lunepick.member.internal.application;

import io.github.cskwork.lunepick.member.internal.domain.PasswordPolicy;
import io.github.cskwork.lunepick.member.internal.persistence.Member;
import jakarta.validation.constraints.*;

public final class AuthDtos {
    private AuthDtos() {}

    public record SignupRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH)
                    String password,
            @NotBlank @Size(max = 100) String name,
            @Pattern(regexp = "^$|^01[016789]-?\\d{3,4}-?\\d{4}$") @Size(max = 20) String phone) {
        public SignupRequest {
            if (email != null) email = Member.normalizeEmail(email);
        }
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 256) String password) {
        public LoginRequest {
            if (email != null) email = Member.normalizeEmail(email);
        }
    }

    public record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {}

    public record SignupResponse(long memberId) {}

    public record TokenResponse(String accessToken, String refreshToken, long expiresInSec) {}
}
