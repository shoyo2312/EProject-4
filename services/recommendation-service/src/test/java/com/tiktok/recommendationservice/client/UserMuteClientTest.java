package com.tiktok.recommendationservice.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UserMuteClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://user-service");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final UserMuteClient client = new UserMuteClient(builder.build());

    @Test
    void mutedIds_readsTheInternalEndpoint() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/7/muted-ids"))
                .andRespond(withSuccess("{\"success\": true, \"data\": [2, 3]}", MediaType.APPLICATION_JSON));

        assertThat(client.mutedIds(7L)).containsExactlyInAnyOrder(2L, 3L);
    }

    /**
     * Fails open, unlike the chat block check: a mute is a preference about the viewer's own feed,
     * not a protection for anyone, and a user-service outage should not empty For You.
     */
    @Test
    void mutedIds_whenUserServiceIsDown_filtersNothing() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/7/muted-ids"))
                .andRespond(withServerError());

        assertThat(client.mutedIds(7L)).isEmpty();
    }
}
