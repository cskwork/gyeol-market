package io.github.cskwork.lunepick.member.internal.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokenAndPasswordRulesTest {
    private final OpaqueTokens tokens = new OpaqueTokens();

    @Test
    void refreshTokenContains256RandomBitsAndIndependentTokensDiffer() {
        String first = tokens.generate();
        assertThat(Base64.getUrlDecoder().decode(first)).hasSize(32);
        assertThat(first).hasSize(43).doesNotContain("+", "/", "=");
        assertThat(tokens.generate()).isNotEqualTo(first);
    }

    @Test
    void onlyStableSha256DigestIsUsedAsPersistedIdentifier() {
        assertThat(tokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(tokens.hash(tokens.generate())).matches("[0-9a-f]{64}");
    }

    @Test
    void passwordLengthMatchesSignupFormWithoutInventingComplexityRequirement() {
        assertThat(PasswordPolicy.valid("abcdefgh")).isTrue();
        assertThat(PasswordPolicy.valid("가".repeat(256))).isTrue();
        assertThat(PasswordPolicy.valid("1234567")).isFalse();
        assertThat(PasswordPolicy.valid("x".repeat(257))).isFalse();
        assertThat(PasswordPolicy.valid("        ")).isFalse();
        assertThat(PasswordPolicy.valid(null)).isFalse();
    }
}
