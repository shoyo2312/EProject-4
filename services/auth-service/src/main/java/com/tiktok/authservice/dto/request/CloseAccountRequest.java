package com.tiktok.authservice.dto.request;

import com.tiktok.authservice.entity.AuthProvider;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;

import java.util.stream.Stream;

/**
 * Proof that the person closing the account is its owner and not someone at a computer left signed
 * in. Exactly one of three, picked by the client from {@code /auth/me}:
 *
 * @param password the account's password, when it has one
 * @param otp      the code {@code /auth/account/closure-code} mailed, for an account with no password
 * @param provider with {@code token}: a fresh sign-in with a provider linked to the account, for one
 *                 with neither a password nor an address
 */
public record CloseAccountRequest(
        String password,
        @Pattern(regexp = "\\d{6}") String otp,
        AuthProvider provider,
        String token
) {

    @AssertTrue(message = "give exactly one of password, otp, or provider with token")
    public boolean isExactlyOneProof() {
        boolean social = provider != null || token != null;
        if (social && (provider == null || isBlank(token))) {
            return false;
        }
        return Stream.of(!isBlank(password), !isBlank(otp), social).filter(given -> given).count() == 1;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
