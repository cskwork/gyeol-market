package io.github.cskwork.lunepick.member.internal.domain;

/** Matches the storefront's required length; character complexity is only a hint. */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 256;

    private PasswordPolicy() {}

    public static boolean valid(String password) {
        return password != null
                && !password.isBlank()
                && password.length() >= MIN_LENGTH
                && password.length() <= MAX_LENGTH;
    }
}
