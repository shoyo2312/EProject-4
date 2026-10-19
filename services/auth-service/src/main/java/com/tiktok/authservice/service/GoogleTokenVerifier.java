package com.tiktok.authservice.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tiktok.authservice.config.OAuthProperties;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.exception.InvalidSocialTokenException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Verifies the Google token the client's SDK produced: an ID token from {@code google_sign_in} on
 * Flutter, an access token from Google Identity Services' token client on web. The web takes the
 * access token because only that flow opens Google's popup from a button we draw ourselves.
 * {@code tokeninfo} answers both with the same {@code aud}/{@code sub}/{@code email} fields.
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

    private static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";

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
    public SocialProfile verify(String token) {
        boolean idToken = isJwt(token);
        TokenInfo info = fetch(idToken ? "id_token" : "access_token", token);
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
                "true".equalsIgnoreCase(info.emailVerified()),
                // Present only when the token was minted with the `profile` scope. Null otherwise,
                // which just leaves a new account on the default avatar.
                idToken ? info.picture() : picture(token));
    }

    /** An ID token is a JWT — three dot-separated parts. Google's access tokens are not. */
    private static boolean isJwt(String token) {
        return token.chars().filter(c -> c == '.').count() == 2;
    }

    private TokenInfo fetch(String param, String token) {
        try {
            return googleRestClient.get()
                    .uri(uri -> uri.path("/tokeninfo").queryParam(param, token).build())
                    .retrieve()
                    .body(TokenInfo.class);
        } catch (HttpClientErrorException e) {
            // 4xx is Google's way of saying the token is bad. Anything else — a timeout, a 5xx —
            // is our outage, not the user's fault, and must not be reported as a bad token.
            throw new InvalidSocialTokenException();
        }
    }

    /**
     * tokeninfo does not carry the picture for an access token; userinfo does. Cosmetic, so any
     * failure just leaves the default avatar instead of failing the login.
     */
    private String picture(String accessToken) {
        try {
            UserInfo info = googleRestClient.get()
                    .uri(USERINFO_URL)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .body(UserInfo.class);
            return info == null ? null : info.picture();
        } catch (RestClientException e) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserInfo(String picture) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenInfo(
            String aud,
            String sub,
            String email,
            @JsonProperty("email_verified") String emailVerified,
            String picture
    ) {
    }
}
