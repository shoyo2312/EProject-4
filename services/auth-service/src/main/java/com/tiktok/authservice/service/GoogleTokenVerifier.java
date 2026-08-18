package com.tiktok.authservice.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tiktok.authservice.config.OAuthProperties;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.exception.InvalidSocialTokenException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Verifies the Google ID token the client's SDK produced — the {@code credential} from Google
 * Identity Services on web, the {@code idToken} from {@code google_sign_in} on Flutter. They are
 * the same kind of token, so one implementation serves every platform.
 *
 * <p>Verification is delegated to Google's {@code tokeninfo} endpoint, which rejects a token whose
 * signature or expiry does not hold. What it cannot decide for us is the {@code aud} claim: it says
 * which of Google's <em>millions</em> of OAuth clients the token was minted for, and a token minted
 * for someone else's app names a real Google user just as convincingly as ours does. Checking it
 * against our own client ids is what makes the rest of the response mean anything.
 *
 * <p>Locally validating the signature against Google's published keys (google-api-client's
 * {@code GoogleIdTokenVerifier}) would save this round trip; it costs a dependency tree we do not
 * otherwise need, and the round trip is one per login, not one per request.
 */
@Component
public class GoogleTokenVerifier implements SocialTokenVerifier {

    private final RestClient googleRestClient;
    private final OAuthProperties properties;

    public GoogleTokenVerifier(RestClient googleRestClient, OAuthProperties properties) {
        this.googleRestClient = googleRestClient;
        this.properties = properties;
    }

    @Override
    public AuthProvider provider() {
        return AuthProvider.GOOGLE;
    }

    @Override
    public SocialProfile verify(String idToken) {
        TokenInfo info = fetch(idToken);
        if (info == null || info.sub() == null) {
            throw new InvalidSocialTokenException();
        }
        if (!properties.google().clientIds().contains(info.aud())) {
            throw new InvalidSocialTokenException();
        }
        return new SocialProfile(
                AuthProvider.GOOGLE,
                info.sub(),
                info.email() == null ? null : info.email().toLowerCase(),
                // Google reports this as the string "true", not a JSON boolean.
                "true".equalsIgnoreCase(info.emailVerified()));
    }

    private TokenInfo fetch(String idToken) {
        try {
            return googleRestClient.get()
                    .uri(uri -> uri.path("/tokeninfo").queryParam("id_token", idToken).build())
                    .retrieve()
                    .body(TokenInfo.class);
        } catch (HttpClientErrorException e) {
            // 4xx is Google's way of saying the token is bad. Anything else — a timeout, a 5xx —
            // is our outage, not the user's fault, and must not be reported as a bad token.
            throw new InvalidSocialTokenException();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenInfo(
            String aud,
            String sub,
            String email,
            @JsonProperty("email_verified") String emailVerified
    ) {
    }
}
