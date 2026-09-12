package com.example.voward;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Salted PBKDF2-HMAC-SHA256 hashing for the recovery key, with no Android dependencies.
 *
 * <p>Standalone so the release activity can check the key without loading the preferences
 * singleton: rung 2 of the escape ladder has to work when the rest of the app does not.</p>
 */
final class RecoveryKeyHash {

    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int PBKDF2_BITS = 256;
    private static final String PBKDF2_PREFIX = "pbkdf2$";

    private RecoveryKeyHash() {}

    /** Empty input hashes to the empty string, which {@link #matches} never accepts. */
    static String create(String input) {
        if (input == null || input.isEmpty()) return "";
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            PBEKeySpec spec = new PBEKeySpec(input.toCharArray(), salt, PBKDF2_ITERATIONS,
                    PBKDF2_BITS);
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            spec.clearPassword();
            return PBKDF2_PREFIX + PBKDF2_ITERATIONS + "$"
                    + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to protect deactivation key", e);
        }
    }

    /** Constant-time comparison against either the current or the legacy stored format. */
    static boolean matches(String stored, String input) {
        if (stored == null || stored.isEmpty() || input == null) return false;
        try {
            if (stored.startsWith(PBKDF2_PREFIX)) {
                String[] parts = stored.split("\\$");
                if (parts.length != 4) return false;
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                PBEKeySpec spec = new PBEKeySpec(input.toCharArray(), salt,
                        Integer.parseInt(parts[1]), expected.length * 8);
                byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).getEncoded();
                spec.clearPassword();
                return MessageDigest.isEqual(expected, actual);
            }
            return MessageDigest.isEqual(
                    stored.getBytes(StandardCharsets.US_ASCII),
                    sha256Hex(input).getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** True when {@code stored} predates PBKDF2 and should be rewritten after a match. */
    static boolean isLegacyFormat(String stored) {
        return stored != null && !stored.isEmpty() && !stored.startsWith(PBKDF2_PREFIX);
    }

    private static String sha256Hex(String input) {
        if (input == null || input.isEmpty()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be present on all Android versions
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
