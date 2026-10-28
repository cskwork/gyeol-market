package io.github.cskwork.lunepick.member.internal.application;

import static org.assertj.core.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class RefreshTokenCipherTest {
    @Test
    void successorIsRecoverableButCiphertextsUseIndependentNonces() {
        var cipher = new RefreshTokenCipher(new byte[32]);
        String first = cipher.encrypt("opaque-refresh-value");
        String second = cipher.encrypt("opaque-refresh-value");
        assertThat(first).isNotEqualTo(second).doesNotContain("opaque-refresh-value");
        assertThat(cipher.decrypt(first)).isEqualTo("opaque-refresh-value");
        assertThat(cipher.decrypt(second)).isEqualTo("opaque-refresh-value");
    }

    @Test
    void alteredCiphertextAndDifferentEncryptionSecretsCannotRecoverToken() {
        var cipher = new RefreshTokenCipher(new byte[32]);
        String encrypted = cipher.encrypt("opaque-refresh-value");
        byte[] altered = Base64.getDecoder().decode(encrypted);
        altered[altered.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(altered)))
                .isInstanceOf(IllegalStateException.class);
        byte[] otherSecret = new byte[32];
        otherSecret[0] = 1;
        assertThatThrownBy(() -> new RefreshTokenCipher(otherSecret).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyMustHaveExactly256Bits() {
        assertThatThrownBy(() -> new RefreshTokenCipher(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshTokenCipher(new byte[33]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
