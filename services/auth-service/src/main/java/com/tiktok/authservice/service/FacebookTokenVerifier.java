package com.tiktok.authservice.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tiktok.authservice.config.OAuthProperties;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.exception.InvalidSocialTokenException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.util.function.Function;

/**
 * Verifies the Facebook access token the client's SDK produced (Facebook JS SDK on web,
 * {@code flutter_facebook_auth} on mobile).
 *
 * <p>Facebook issues no ID token, so there is nothing to check a signature on: the token is an
 * opaque handle and only Facebook can say what it means. {@code /debug_token} is that question, and
 * its answer carries the two facts that matter — whether the token is still valid, and
 * <em>which app it was issued to</em>. The second is the one that does the security work: any app
 * on Facebook can obtain a token for the same user, and without comparing {@code app_id} to our own
 * a token harvested by an unrelated app would log its bearer in here as that user.
 */
@Component
public class FacebookTokenVerifier implements SocialTokenVerifier {

    /** A JWT and nothing else has two dots; a Graph access token is an opaque blob without any. */
    private static final int JWT_SEGMENT_SEPARATORS = 2;

    private final RestClient facebookRestClient;
    private final OAuthProperties properties;
    private final FacebookLimitedLoginVerifier limitedLoginVerifier;

    public FacebookTokenVerifier(
            RestClient facebookRestClient,
            OAuthProperties properties,
            FacebookLimitedLoginVerifier limitedLoginVerifier) {
        this.facebookRestClient = facebookRestClient;
        this.properties = properties;
        this.limitedLoginVerifier = limitedLoginVerifier;
    }

    @Override
    public AuthProvider provider() {
        return AuthProvider.FACEBOOK;
    }

    @Override
    public SocialProfile verify(String accessToken) {
        // iOS downgrades to Limited Login whenever App Tracking Transparency was not granted, and
        // then the client has an OIDC token rather than a Graph one. It is a different kind of
        // evidence and is checked differently — see FacebookLimitedLoginVerifier.
        if (isJwt(accessToken)) {
            return limitedLoginVerifier.verify(accessToken);
        }
        DebugTokenData data = debugToken(accessToken);
        if (data == null
                || !data.isValid()
                || data.userId() == null
                || !properties.facebook().appId().equals(data.appId())) {
            throw new InvalidSocialTokenException();
        }

        // The uid comes from /debug_token, which we have just authenticated with our app secret;
        // /me is asked only for the address and the picture, neither of which /debug_token carries.
        MeResponse me = me(accessToken);
        String email = me.email();

        // Facebook never states whether it has verified the address, so we must assume it has not.
        // That means a Facebook login never auto-links to an existing account by email — it can
        // only create a new one or match a uid we have already seen.
        return new SocialProfile(
                AuthProvider.FACEBOOK,
                data.userId(),
                email == null ? null : email.toLowerCase(),
                false,
                me.pictureUrl());
    }

    private static boolean isJwt(String token) {
        return token != null && token.chars().filter(c -> c == '.').count() == JWT_SEGMENT_SEPARATORS;
    }

    private DebugTokenData debugToken(String accessToken) {
        // The app token is literally "{app-id}|{app-secret}"; it is what proves the question is
        // coming from us, and is the reason the secret may never leave the server.
        String appToken = properties.facebook().appId() + "|" + properties.facebook().appSecret();
        DebugTokenResponse response = call(uri -> uri.path("/debug_token")
                .queryParam("input_token", accessToken)
                .queryParam("access_token", appToken)
                .build(), DebugTokenResponse.class);
        return response == null ? null : response.data();
    }

    private MeResponse me(String accessToken) {
        MeResponse response = call(uri -> uri.path("/me")
                .queryParam("fields", "id,email,picture.type(large)")
                .queryParam("access_token", accessToken)
                .build(), MeResponse.class);
        if (response == null) {
            throw new InvalidSocialTokenException();
        }
        return response;
    }

    private <T> T call(Function<UriBuilder, URI> uri, Class<T> type) {
        try {
            return facebookRestClient.get().uri(uri).retrieve().body(type);
        } catch (HttpClientErrorException e) {
            // Graph answers 4xx for an expired or revoked token. A 5xx or a timeout is Facebook
            // being down and stays an error of ours, not a rejected login.
            throw new InvalidSocialTokenException();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DebugTokenResponse(DebugTokenData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DebugTokenData(
            @JsonProperty("app_id") String appId,
            @JsonProperty("is_valid") boolean isValid,
            @JsonProperty("user_id") String userId
    ) {
    }

    /** {@code email} is absent whenever the permission was declined or the account has none. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MeResponse(String id, String email, Picture picture) {

        /**
         * Null unless Facebook has a real picture: the field is always present, but for an account
         * that never uploaded one it points at the grey silhouette, and storing that would be worse
         * than our own default avatar rather than better.
         */
        String pictureUrl() {
            if (picture == null || picture.data() == null || picture.data().isSilhouette()) {
                return null;
            }
            return picture.data().url();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Picture(PictureData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PictureData(String url, @JsonProperty("is_silhouette") boolean isSilhouette) {
    }
}
