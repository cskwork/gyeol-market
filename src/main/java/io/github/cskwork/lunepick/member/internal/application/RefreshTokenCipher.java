package io.github.cskwork.lunepick.member.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Recover a successor only during the short refresh race grace; never store its plaintext. */
public final class RefreshTokenCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenCipher(byte[] secret) {
        Objects.requireNonNull(secret, "Refresh encryption key is required");
        if (secret.length != 32)
            throw new IllegalArgumentException("Refresh encryption requires a 32-byte AES-256 key");
        key = new SecretKeySpec(secret.clone(), "AES");
    }

    public String encrypt(String token) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Refresh token encryption failed", e);
        }
    }

    public String decrypt(String value) {
        try {
            byte[] packed = Base64.getDecoder().decode(value);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, packed, 0, 12));
            return new String(
                    cipher.doFinal(packed, 12, packed.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Refresh token recovery failed", e);
        }
    }
}
