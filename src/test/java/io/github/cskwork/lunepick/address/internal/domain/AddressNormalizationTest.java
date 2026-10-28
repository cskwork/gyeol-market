package io.github.cskwork.lunepick.address.internal.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AddressNormalizationTest {
    @Test
    void trimsJavaScriptWhitespaceAndFormatsOnlyBareMobileNumbers() {
        assertThat(AddressNormalization.trim("\uFEFF\u00a0 서울 \u00a0")).isEqualTo("서울");
        assertThat(AddressNormalization.trim(null)).isEmpty();
        assertThat(AddressNormalization.phone(" 01012345678 ")).isEqualTo("010-1234-5678");
        assertThat(AddressNormalization.phone("0111234567")).isEqualTo("011-123-4567");
        assertThat(AddressNormalization.phone("010-1234-5678")).isEqualTo("010-1234-5678");
        assertThat(AddressNormalization.phone("02-123-4567")).isEqualTo("02-123-4567");
        assertThat(AddressNormalization.detail(" 101호 ")).isEqualTo("101호");
        assertThat(AddressNormalization.detail(" ")).isNull();
    }
}
