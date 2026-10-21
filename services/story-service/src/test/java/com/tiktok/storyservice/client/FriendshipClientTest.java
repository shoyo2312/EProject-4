package com.tiktok.storyservice.client;

import com.tiktok.storyservice.client.FriendshipClient.Relationship;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FriendshipClientTest {

    private MockRestServiceServer server;
    private FriendshipClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://users");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new FriendshipClient(builder.build());
    }

    @Test
    void asksTheInternalEndpointAsTheViewer() {
        server.expect(requestTo("http://users/api/v1/users/internal/friendship?userA=9&userB=2"))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"userId\":2,\"friends\":false,"
                        + "\"following\":true,\"requested\":false,\"privateAccount\":true}}", MediaType.APPLICATION_JSON));

        assertThat(client.relationship(9L, 2L)).isEqualTo(new Relationship(false, true, true));
    }

    @Test
    void anAnonymousViewerIsAskedWithoutUserA() {
        server.expect(requestTo("http://users/api/v1/users/internal/friendship?userB=2"))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"userId\":2,\"privateAccount\":false}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.relationship(null, 2L)).isEqualTo(new Relationship(false, false, false));
    }

    @Test
    void anUnansweredLookupHidesTheOwnersStories() {
        server.expect(requestTo("http://users/api/v1/users/internal/friendship?userA=9&userB=2"))
                .andRespond(withServerError());

        assertThat(client.relationship(9L, 2L)).isEqualTo(Relationship.UNKNOWN);
        assertThat(Relationship.UNKNOWN.privateAccount()).isTrue();
        assertThat(Relationship.UNKNOWN.following()).isFalse();
    }
}
