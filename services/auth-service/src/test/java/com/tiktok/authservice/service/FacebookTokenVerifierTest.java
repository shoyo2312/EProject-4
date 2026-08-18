package com.tiktok.authservice.service;

import com.tiktok.authservice.config.OAuthProperties;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.exception.InvalidSocialTokenException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FacebookTokenVerifierTest {

    private static final String OUR_APP_ID = "app-1";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://graph.facebook.com/v21.0");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final FacebookTokenVerifier verifier = new FacebookTokenVerifier(
            builder.build(),
            new OAuthProperties(
                    new OAuthProperties.Google(List.of("web-client-id")),
                    new OAuthProperties.Facebook(OUR_APP_ID, "app-secret")));

    /**
     * Facebook hands every app a token for the same user, and the token itself says nothing. Only
     * app_id separates one our sign-in button produced from one collected by an unrelated app.
     */
    @Test
    void rejectsTokenIssuedToAnotherApp() {
        debugTokenReturns("""
                {"data":{"app_id":"some-other-app","is_valid":true,"user_id":"7654321098765432"}}
                """);

        assertThatThrownBy(() -> verifier.verify("token-from-another-app"))
                .isInstanceOf(InvalidSocialTokenException.class);
    }

    @Test
    void rejectsExpiredOrRevokedToken() {
        debugTokenReturns("""
                {"data":{"app_id":"app-1","is_valid":false,"user_id":"7654321098765432"}}
                """);

        assertThatThrownBy(() -> verifier.verify("revoked-token"))
                .isInstanceOf(InvalidSocialTokenException.class);
    }

    /**
     * The ordinary case, not an exotic one: the email permission can be declined at the consent
     * screen and an account registered with a phone number has no address at all.
     */
    @Test
    void acceptsAccountWithNoEmailAndNeverClaimsItIsVerified() {
        debugTokenReturns("""
                {"data":{"app_id":"app-1","is_valid":true,"user_id":"7654321098765432"}}
                """);
        server.expect(requestTo(containsString("/me")))
                .andRespond(withSuccess("{\"id\":\"7654321098765432\"}", MediaType.APPLICATION_JSON));

        SocialProfile profile = verifier.verify("good-token");

        assertThat(profile.provider()).isEqualTo(AuthProvider.FACEBOOK);
        assertThat(profile.uid()).isEqualTo("7654321098765432");
        assertThat(profile.email()).isNull();
        // False even when an address is present: Facebook never states that it verified one, and
        // a wrongly trusted address is how an attacker claims someone else's account.
        assertThat(profile.emailVerified()).isFalse();
    }

    private void debugTokenReturns(String json) {
        server.expect(requestTo(containsString("/debug_token")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }
}
