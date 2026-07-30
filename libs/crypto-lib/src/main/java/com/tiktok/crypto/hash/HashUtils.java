package com.tiktok.crypto.hash;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * BCrypt for passwords (slow, salted, one-way). SHA-256 for non-secret lookups
 * (e.g. deterministic hash of an email for indexing) — never use sha256 for passwords.
 */
public final class HashUtils {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    private HashUtils() {
    }

    public static String bcryptHash(String raw) {
        return BCRYPT.encode(raw);
    }

    public static boolean bcryptMatches(String raw, String hashed) {
        return BCRYPT.matches(raw, hashed);
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
