package com.tiktok.authservice.service;

import com.tiktok.authservice.config.OAuthProperties;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.exception.InvalidSocialTokenException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleTokenVerifierTest {

    private static final String OUR_CLIENT_ID = "web-client-id.apps.googleusercontent.com";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://oauth2.googleapis.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GoogleTokenVerifier verifier = new GoogleTokenVerifier(
            builder.build(),
            new OAuthProperties(
                    new OAuthProperties.Google(List.of(OUR_CLIENT_ID)),
                    new OAuthProperties.Facebook("app-1", "secret")));

    /**
     * The check the whole implementation exists for. Google will happily confirm a token minted for
     * any of its millions of OAuth clients: the signature is valid, the user is real, and the only
     * thing separating it from ours is the aud claim.
     */
    @Test
    void rejectsTokenMintedForAnotherApplication() {
        respondWith("""
                {"aud":"someone-elses-client-id","sub":"107841234567890123456",
                 "email":"victim@example.com","email_verified":"true"}
                """);

        assertThatThrownBy(() -> verifier.verify("header.from-another-app.sig"))
                .isInstanceOf(InvalidSocialTokenException.class);
    }

    @Test
    void acceptsTokenMintedForUsAndReadsTheVerifiedFlagFromAString() {
        respondWith("""
                {"aud":"%s","sub":"107841234567890123456",
                 "email":"A@Example.com","email_verified":"true"}
                """.formatted(OUR_CLIENT_ID));

        SocialProfile profile = verifier.verify("header.payload.sig");

        assertThat(profile.provider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(profile.uid()).isEqualTo("107841234567890123456");
        assertThat(profile.email()).isEqualTo("a@example.com");
        // Google sends the string "true", not a JSON boolean — read as a boolean it would be false
        // and every Google account would land as unverified, never linking to an existing user.
        assertThat(profile.emailVerified()).isTrue();
    }

    @Test
    void treatsGooglesRejectionAsABadTokenRatherThanAnOutage() {
        server.expect(requestTo(containsString("/tokeninfo")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> verifier.verify("header.expired.sig"))
                .isInstanceOf(InvalidSocialTokenException.class);
    }

    /** The web's token client hands us an access token; its picture comes from userinfo. */
    @Test
    void checksAnAccessTokenTheSameWayAndTakesThePictureFromUserinfo() {
        server.expect(requestTo(containsString("/tokeninfo?access_token=ya29.access")))
                .andRespond(withSuccess("""
                        {"aud":"%s","sub":"107841234567890123456",
                         "email":"a@example.com","email_verified":"true"}
                        """.formatted(OUR_CLIENT_ID), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/userinfo")))
                .andExpect(header("Authorization", "Bearer ya29.access"))
                .andRespond(withSuccess("{\"picture\":\"https://lh3.example/p.jpg\"}",
                        MediaType.APPLICATION_JSON));

        SocialProfile profile = verifier.verify("ya29.access");

        assertThat(profile.uid()).isEqualTo("107841234567890123456");
        assertThat(profile.avatarUrl()).isEqualTo("https://lh3.example/p.jpg");
        server.verify();
    }

    private void respondWith(String json) {
        server.expect(requestTo(containsString("/tokeninfo")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }
}
