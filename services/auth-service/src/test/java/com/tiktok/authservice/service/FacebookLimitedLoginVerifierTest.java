package com.tiktok.authservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.authservice.config.OAuthProperties;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.exception.InvalidSocialTokenException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwks;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Limited Login is what iOS falls back to when App Tracking Transparency was declined, so this is
 * the path most iPhone sign-ins actually take — not an edge case.
 */
class FacebookLimitedLoginVerifierTest {

    private static final String OUR_APP_ID = "app-1";
    private static final String ISSUER = "https://www.facebook.com";
    private static final String KID = "kid-1";

    private static final KeyPair FACEBOOK_KEYS = Jwts.SIG.RS256.keyPair().build();
    private static final KeyPair IMPOSTOR_KEYS = Jwts.SIG.RS256.keyPair().build();

    private final RestClient.Builder builder = RestClient.builder().baseUrl(ISSUER);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final FacebookLimitedLoginVerifier verifier = new FacebookLimitedLoginVerifier(
            builder.build(),
            new OAuthProperties(
                    new OAuthProperties.Google(List.of("web-client-id")),
                    new OAuthProperties.Facebook(OUR_APP_ID, "app-secret")));

    @Test
    void acceptsTokenSignedByFacebookForOurApp() {
        publishedKeysAre(jwks());

        SocialProfile profile = verifier.verify(token(OUR_APP_ID, FACEBOOK_KEYS, "USER@Example.com"));

        assertThat(profile.provider()).isEqualTo(AuthProvider.FACEBOOK);
        assertThat(profile.uid()).isEqualTo("7654321098765432");
        assertThat(profile.email()).isEqualTo("user@example.com");
        // Facebook asserts nothing about the address here either, so it may never auto-link.
        assertThat(profile.emailVerified()).isFalse();
    }

    /** Same signature, same issuer — only `aud` separates our login from another app's. */
    @Test
    void rejectsTokenMintedForAnotherApp() {
        publishedKeysAre(jwks());

        assertThatThrownBy(() -> verifier.verify(token("some-other-app", FACEBOOK_KEYS, null)))
                .isInstanceOf(InvalidSocialTokenException.class);
    }

    @Test
    void rejectsTokenSignedWithAKeyFacebookDoesNotPublish() {
        publishedKeysAre(jwks());

        assertThatThrownBy(() -> verifier.verify(token(OUR_APP_ID, IMPOSTOR_KEYS, null)))
                .isInstanceOf(InvalidSocialTokenException.class);
    }

    @Test
    void rejectsExpiredToken() {
        publishedKeysAre(jwks());

        String expired = Jwts.builder()
                .header().keyId(KID).and()
                .issuer(ISSUER)
                .audience().add(OUR_APP_ID).and()
                .subject("7654321098765432")
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(FACEBOOK_KEYS.getPrivate(), Jwts.SIG.RS256)
                .compact();

        assertThatThrownBy(() -> verifier.verify(expired))
                .isInstanceOf(InvalidSocialTokenException.class);
    }

    private String token(String audience, KeyPair signer, String email) {
        var builder = Jwts.builder()
                .header().keyId(KID).and()
                .issuer(ISSUER)
                .audience().add(audience).and()
                .subject("7654321098765432")
                .expiration(Date.from(Instant.now().plusSeconds(300)));
        if (email != null) {
            builder.claim("email", email);
        }
        return builder.signWith(signer.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static String jwks() {
        Map<String, Object> jwk = Jwks.builder().key(FACEBOOK_KEYS.getPublic()).id(KID).build();
        try {
            return "{\"keys\":[" + new ObjectMapper().writeValueAsString(jwk) + "]}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void publishedKeysAre(String jwks) {
        server.expect(requestTo(containsString("/.well-known/oauth/openid/jwks/")))
                .andRespond(withSuccess(jwks, MediaType.APPLICATION_JSON));
    }
}
