package com.tiktok.chatservice.client;

import com.tiktok.chatservice.exception.DmSettingsUnavailableException;
import org.junit.jupiter.api.BeforeEach;
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

    private static final String URL = "http://user-service/api/v1/users/internal/friendship?userA=1&userB=2";

    private MockRestServiceServer server;
    private FriendshipClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new FriendshipClient(builder, "http://user-service");
    }

    /**
     * The tokenless internal path, not {@code /users/{id}/friendship}: a conversation opens from
     * WebSocket frames too, and there is no bearer token to forward on one.
     */
    @Test
    void friendship_isAskedOfTheInternalPath() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"userId\":2,\"friends\":true}}", MediaType.APPLICATION_JSON));

        assertThat(client.areFriends(1L, 2L)).isTrue();
    }

    /** Fails closed: "only my friends" must not become "everyone" during an outage. */
    @Test
    void userServiceDown_isAnErrorRatherThanAFalse() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.areFriends(1L, 2L))
                .isInstanceOf(DmSettingsUnavailableException.class);
    }
}
