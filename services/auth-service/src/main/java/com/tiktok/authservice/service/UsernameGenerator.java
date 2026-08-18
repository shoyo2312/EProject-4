package com.tiktok.authservice.service;

import com.tiktok.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Invents a username for an account created by a social login, where the user was never asked for
 * one.
 *
 * <p>The address's local part is the starting point because it is the name the user already
 * answers to. It is not usable as-is: it may collide with an account we already have, may be too
 * short or too long, and may contain characters a username should not carry.
 */
@Component
@RequiredArgsConstructor
public class UsernameGenerator {

    private static final int MIN_LENGTH = 3;   // matches RegisterRequest's @Size
    private static final int MAX_LENGTH = 50;
    private static final int SUFFIX_BOUND = 10_000;
    private static final int MAX_ATTEMPTS = 10;
    private static final String FALLBACK_BASE = "user";

    private final UserRepository userRepository;
    private final SecureRandom random = new SecureRandom();

    public String generate(String email) {
        String base = baseFrom(email);

        if (!userRepository.existsByUsernameIgnoreCaseAndDeletedAtIsNull(base)) {
            return base;
        }

        // ponytail: retry with random digits rather than a counter — a counter would need a scan of
        // every taken name, and the collision rate stays negligible while a base is not popular.
        // If a base ever exhausts all ten attempts the login fails and the user retries; move to a
        // sequence table only if that ever shows up.
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = truncate(base, MAX_LENGTH - 4) + random.nextInt(SUFFIX_BOUND);
            if (!userRepository.existsByUsernameIgnoreCaseAndDeletedAtIsNull(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a free username from base " + base);
    }

    /**
     * The local part, reduced to letters, digits, dot and underscore. An address may be entirely
     * non-latin, or absent — Facebook gives no address for an account registered with a phone
     * number — so the result can be empty, and then a generic base stands in.
     */
    private String baseFrom(String email) {
        if (email == null) {
            return FALLBACK_BASE;
        }

        String local = email.substring(0, Math.max(email.indexOf('@'), 0))
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._]", "");

        return local.length() < MIN_LENGTH ? FALLBACK_BASE : truncate(local, MAX_LENGTH);
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
