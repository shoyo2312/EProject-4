package com.tiktok.chatservice.client;

import com.tiktok.chatservice.exception.StoryCheckUnavailableException;
import com.tiktok.common.http.InternalApiToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StoryClientTest {
    private MockRestServiceServer server;
    private StoryClient client;
    private static final InternalApiToken TOKEN = InternalApiToken.from(new MockEnvironment()
            .withProperty("INTERNAL_API_TOKEN", "internal-token-for-tests-0123456789abcdef"));

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new StoryClient(builder, "http://story", TOKEN);
    }

    @Test
    void oneBatchUsesTheInternalTokenAndAbsentIdsAreUnavailable() {
        server.expect(requestTo("http://story/api/v1/stories/internal/viewable?viewerId=1&ids=a,b"))
                .andExpect(header(InternalApiToken.HEADER, TOKEN.value()))
                .andRespond(withSuccess("{\"success\":true,\"data\":[{\"id\":\"a\",\"userId\":7,\"mediaType\":\"IMAGE\",\"mediaUrl\":\"u\"}]}", MediaType.APPLICATION_JSON));
        var cards = client.viewable(1L, List.of("a", "b", "a"));
        assertThat(cards.get("a").state()).isEqualTo("AVAILABLE");
        assertThat(cards.get("a").ownerId()).isEqualTo(7L);
        assertThat(cards.get("b").state()).isEqualTo("UNAVAILABLE");
        assertThat(cards.get("b").mediaUrl()).isNull();
        server.verify();
    }

    @Test
    void outageFailsClosedOnSendAndDrawsUnknownOnHistory() {
        server.expect(requestTo("http://story/api/v1/stories/internal/viewable?viewerId=1&ids=a")).andRespond(withServerError());
        assertThatThrownBy(() -> client.viewable(1L, List.of("a"))).isInstanceOf(StoryCheckUnavailableException.class);
        server.reset();
        server.expect(requestTo("http://story/api/v1/stories/internal/viewable?viewerId=1&ids=a")).andRespond(withServerError());
        assertThat(client.hydrate(1L, List.of("a")).get("a").state()).isEqualTo("UNKNOWN");
    }
}
