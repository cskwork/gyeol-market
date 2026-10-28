package io.github.cskwork.lunepick.shared;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PageQueryTest {
    @Test
    void defaultsAndValidIntegersHaveExactLongOffsets() {
        assertThat(PageQuery.from(null, null, 20)).isEqualTo(new PageQuery(0, 20));
        assertThat(PageQuery.from("2", "3", 20)).isEqualTo(new PageQuery(2, 3));
        assertThat(PageQuery.from("2147483647", "100", 20).offset()).isEqualTo(214748364700L);
        assertThat(new PageQuery(1, 3).limit()).isEqualTo(3);
        assertThat(new PageMeta(0, 20, 0).page()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "bad",
                "-1",
                "1.5",
                "1.0",
                "1e0",
                "0x2",
                "0b10",
                "0o2",
                "Infinity",
                "NaN",
                "+1",
                "2147483648"
            })
    void garbageIs400ForEitherPageParameter(String value) {
        assertThatThrownBy(() -> PageQuery.from(value, null, 20))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> PageQuery.from(null, value, 20)).isInstanceOf(ApiException.class);
    }

    @Test
    void sizeRangeAndDirectConstructionAreValidated() {
        for (String size : new String[] {"0", "101"}) {
            assertThatThrownBy(() -> PageQuery.from(null, size, 20))
                    .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> new PageQuery(-1, 20)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new PageQuery(0, 101)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new PageMeta(0, 20, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
