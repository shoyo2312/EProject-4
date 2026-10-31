package com.tiktok.chatservice.client;

import com.tiktok.chatservice.exception.VideoCheckUnavailableException;
import com.tiktok.common.http.InternalApiToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class VideoClientTest {
    private MockRestServiceServer server;
    private VideoClient client;
    private static final InternalApiToken TOKEN = InternalApiToken.from(new MockEnvironment()
            .withProperty("INTERNAL_API_TOKEN", "internal-token-for-tests-0123456789abcdef"));

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new VideoClient(builder, "http://video", TOKEN);
    }

    @Test
    void oneBatchUsesInternalTokenAndMissingIdsHaveNoMetadata() {
        server.expect(requestTo("http://video/api/v1/videos/internal/viewable?viewerId=1&ids=123,456"))
                .andExpect(header(InternalApiToken.HEADER, TOKEN.value()))
                .andRespond(withSuccess("{\"success\":true,\"data\":[{\"id\":\"123\",\"userId\":7,\"title\":\"title\",\"thumbnailUrl\":\"thumb\",\"durationSeconds\":5}]}", MediaType.APPLICATION_JSON));
        var cards = client.viewable(1L, List.of("123", "456", "123"));
        assertThat(cards.get("123").state()).isEqualTo("AVAILABLE");
        assertThat(cards.get("123").ownerId()).isEqualTo(7L);
        assertThat(cards.get("456").state()).isEqualTo("UNAVAILABLE");
        assertThat(cards.get("456").thumbnailUrl()).isNull();
        server.verify();
    }

    @Test
    void outageFailsClosedOnSend() {
        server.expect(anything()).andRespond(withServerError());
        assertThatThrownBy(() -> client.viewable(1L, List.of("123")))
                .isInstanceOf(VideoCheckUnavailableException.class).extracting("code").isEqualTo("VIDEO_CHECK_UNAVAILABLE");
    }

    @Test
    void readOutageRendersUnknownWithoutPretendingVideoWasDeleted() {
        server.expect(anything()).andRespond(withServerError());
        var card = client.hydrate(1L, List.of("123")).get("123");
        assertThat(card.state()).isEqualTo("UNKNOWN");
        assertThat(card.thumbnailUrl()).isNull();
        assertThat(card.title()).isNull();
        assertThat(card.ownerId()).isNull();
    }

    @Test
    void emptyOrUnsuccessfulBodyIsAnOutage() {
        server.expect(anything()).andRespond(withSuccess("{\"success\":false}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.viewable(1L, List.of("123"))).isInstanceOf(VideoCheckUnavailableException.class);
    }

    @Test
    void largePagesAreChunkedAtTheFixedInternalEndpointCap() {
        server.expect(anything()).andRespond(withSuccess("{\"success\":true,\"data\":[]}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"success\":true,\"data\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.hydrate(1L, java.util.stream.IntStream.range(0, 51).mapToObj(String::valueOf).toList())).hasSize(51);
        server.verify();
    }

    @Test
    void unavailableAndUnknownCardsSerializeOnlyTheIdAndState() {
        var json = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        assertThat(json.writeValueAsString(com.tiktok.chatservice.dto.response.VideoCard.unavailable("123")))
                .isEqualTo("{\"videoId\":\"123\",\"state\":\"UNAVAILABLE\"}");
        assertThat(json.writeValueAsString(com.tiktok.chatservice.dto.response.VideoCard.unknown("123")))
                .isEqualTo("{\"videoId\":\"123\",\"state\":\"UNKNOWN\"}");
        assertThat(json.writeValueAsString(com.tiktok.chatservice.dto.response.VideoCard.unknown("123").minimal()))
                .isEqualTo("{\"state\":\"UNKNOWN\"}");
    }
}
