package io.github.cskwork.lunepick.payment.internal.domain;

import io.github.cskwork.lunepick.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticates the bytes received from the provider, before JSON decoding. */
public final class WebhookSignature {
    private final SecretKeySpec key;
    private final Clock clock;

    public WebhookSignature(String secret) {
        this(secret, Clock.systemUTC());
    }

    public WebhookSignature(String secret, Clock clock) {
        this.clock = clock;
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException(
                    "Webhook secret must contain at least 32 UTF-8 bytes");
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public void verify(byte[] body, String timestamp, String signature) {
        try {
            long seconds = Long.parseLong(timestamp);
            if (!Long.toString(seconds).equals(timestamp)
                    || Duration.between(Instant.ofEpochSecond(seconds), clock.instant())
                                    .abs()
                                    .compareTo(Duration.ofMinutes(5))
                            > 0) throw invalid();
        } catch (RuntimeException malformed) {
            throw invalid();
        }

        byte[] supplied = new byte[0];
        if (signature != null && signature.matches("[a-fA-F0-9]{64}"))
            supplied = HexFormat.of().parseHex(signature);
        if (!MessageDigest.isEqual(hmac(body, timestamp), supplied)) throw invalid();
    }

    public String payloadHash(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private ApiException invalid() {
        return new ApiException(401, "INVALID_WEBHOOK_SIGNATURE", "웹훅 서명이 올바르지 않습니다.");
    }

    private byte[] hmac(byte[] body, String timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal(body);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", unavailable);
        }
    }
}
