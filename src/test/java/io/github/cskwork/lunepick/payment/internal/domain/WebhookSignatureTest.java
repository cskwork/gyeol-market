package io.github.cskwork.lunepick.payment.internal.domain;

import static org.assertj.core.api.Assertions.*;

import io.github.cskwork.lunepick.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final Instant now = Instant.parse("2026-10-28T00:00:00Z");
    private final WebhookSignature signatures =
            new WebhookSignature(SECRET, Clock.fixed(now, ZoneOffset.UTC));
    private final byte[] body =
            "{\"eventId\":\"evt-1\",\"amount\":10}".getBytes(StandardCharsets.UTF_8);

    @Test
    void signedTimestampAndRawBytesAreAuthenticated() throws Exception {
        String time = Long.toString(now.getEpochSecond());
        String signature = "5f99177fccd5314c234d8b27d94d13fce0b63b72d21252d6ac860d1f688006a1";
        assertThatCode(() -> signatures.verify(body, time, signature.toUpperCase()))
                .doesNotThrowAnyException();
        assertThat(signatures.payloadHash(body))
                .isEqualTo("0c889d8c843fa88073d75d850b1bd3b95064643a3644edb2bd73ff889859ecbd");
        assertThatThrownBy(() -> signatures.verify("different".getBytes(), time, signature))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () ->
                                signatures.verify(
                                        body, Long.toString(now.getEpochSecond() + 1), signature))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void fiveMinuteBoundaryAcceptedButStaleAndFutureTimestampsRejected() throws Exception {
        for (long offset : new long[] {-300, 300}) {
            String time = Long.toString(now.getEpochSecond() + offset);
            String signed = sign(time, body);
            assertThatCode(() -> signatures.verify(body, time, signed)).doesNotThrowAnyException();
        }
        for (long offset : new long[] {-301, 301}) {
            String time = Long.toString(now.getEpochSecond() + offset);
            String signed = sign(time, body);
            assertThatThrownBy(() -> signatures.verify(body, time, signed))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void missingMalformedOrWrongCredentialsAreRejected() {
        for (String invalid :
                new String[] {null, "", "invalid", "0".repeat(64), "f".repeat(63), "g".repeat(64)})
            assertThatThrownBy(
                            () ->
                                    signatures.verify(
                                            body, Long.toString(now.getEpochSecond()), invalid))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.status()).isEqualTo(401));
        for (String timestamp : new String[] {null, "", "tomorrow", "999999999999999999999"})
            assertThatThrownBy(() -> signatures.verify(body, timestamp, "0".repeat(64)))
                    .isInstanceOf(ApiException.class);
    }

    @Test
    void secretsRequireAtLeast32Utf8Bytes() {
        assertThatThrownBy(() -> new WebhookSignature(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookSignature("x".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new WebhookSignature("가".repeat(11))).doesNotThrowAnyException();
    }

    private String sign(String timestamp, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(mac.doFinal(data));
    }
}
