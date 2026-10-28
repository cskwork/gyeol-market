package io.github.cskwork.lunepick.member.internal.config;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Dedicated AES-256 key material, independent from JWT signing keys. */
@ConfigurationProperties("lunepick.auth.refresh-cipher")
public record RefreshCipherProperties(String secret) {
    public RefreshCipherProperties {
        if (secret != null) keyBytes(secret);
    }

    public byte[] keyBytes() {
        return keyBytes(secret);
    }

    private static byte[] keyBytes(String value) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length == 32) return bytes;
        } catch (IllegalArgumentException | NullPointerException malformed) {
            // Configuration errors never echo key material.
        }
        throw new IllegalArgumentException(
                "lunepick.auth.refresh-cipher.secret must be base64 encoding of exactly 32 bytes");
    }
}
