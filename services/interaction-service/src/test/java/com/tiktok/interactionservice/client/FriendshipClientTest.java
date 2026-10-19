package com.tiktok.interactionservice.client;

import com.tiktok.interactionservice.exception.PrivacyCheckUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FriendshipClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://user-service");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final FriendshipClient client = new FriendshipClient(builder.build());

    /** A public account's tab is anyone's, the anonymous caller's included — asked without a viewer. */
    @Test
    void canSeeFollowerContent_publicAccount_isOpenToAnyone() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/friendship?userB=7"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"userId": 7, "friends": false, "following": false, "requested": false, "privateAccount": false}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.canSeeFollowerContent(7L, null)).isTrue();
        server.verify();
    }

    @Test
    void canSeeFollowerContent_privateAccount_onlyForAFollower() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/friendship?userB=7&userA=5"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"following": true, "privateAccount": true}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://user-service/api/v1/users/internal/friendship?userB=7&userA=6"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"following": false, "privateAccount": true}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.canSeeFollowerContent(7L, 5L)).isTrue();
        assertThat(client.canSeeFollowerContent(7L, 6L)).isFalse();
        server.verify();
    }

    /**
     * Fails closed, but not as "private": a lookup that failed must not open a private account's
     * tab, and must not tell the client that a public account is private either.
     */
    @Test
    void canSeeFollowerContent_whenUserServiceFails_isUnavailableRatherThanPrivate() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/friendship?userB=7&userA=5"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.canSeeFollowerContent(7L, 5L))
                .isInstanceOf(PrivacyCheckUnavailableException.class);
        server.verify();
    }

    @Test
    void canSeeFollowerContent_answerWithoutData_isUnavailableRatherThanPrivate() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/friendship?userB=7"))
                .andRespond(withSuccess("""
                        {"success": true, "data": null}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.canSeeFollowerContent(7L, null))
                .isInstanceOf(PrivacyCheckUnavailableException.class);
        server.verify();
    }
}
