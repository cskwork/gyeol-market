package io.github.cskwork.lunepick.address.internal.domain;

/** The storefront's normalizeAddress rules, including pasted mobile numbers. */
public final class AddressNormalization {
    private AddressNormalization() {}

    public static String trim(String text) {
        return text == null ? "" : text.replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
    }

    public static String phone(String text) {
        return trim(text).replaceFirst("^(01[016789])(\\d{3,4})(\\d{4})$", "$1-$2-$3");
    }

    public static String detail(String text) {
        String trimmed = trim(text);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
