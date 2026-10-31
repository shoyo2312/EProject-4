package com.tiktok.notificationservice.client;

import com.tiktok.notificationservice.client.StoryAudienceClient.Page;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StoryAudienceClientTest {

    private static final String AUDIENCE_URI = "http://user/api/v1/users/internal/story-audience";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://user");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final StoryAudienceClient client = new StoryAudienceClient(builder.build());

    @Test
    void page_continuesAfterTheCursorAndReadsTheNextOne() {
        server.expect(requestTo(AUDIENCE_URI + "?userId=7&scope=FRIENDS&after=3&size=500")).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"userIds\":[11,12],\"next\":12}}", MediaType.APPLICATION_JSON));

        assertThat(client.page(7L, "FRIENDS", 3L)).isEqualTo(new Page(List.of(11L, 12L), 12L));
        server.verify();
    }

    /** The first page has no cursor, so {@code after} is left out of the query, not sent empty. */
    @Test
    void page_theFirstPageSendsNoCursor() {
        server.expect(requestTo(AUDIENCE_URI + "?userId=7&scope=FOLLOWERS&size=500")).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"userIds\":[13],\"next\":null}}", MediaType.APPLICATION_JSON));

        assertThat(client.page(7L, "FOLLOWERS", null)).isEqualTo(new Page(List.of(13L), null));
        server.verify();
    }

    /** A failure must reach kafka-lib: a swallowed one would end the fan-out early and call it done. */
    @Test
    void page_aFailure_propagates() {
        server.expect(requestTo(AUDIENCE_URI + "?userId=7&scope=FOLLOWERS&size=500")).andRespond(withServerError());

        assertThatThrownBy(() -> client.page(7L, "FOLLOWERS", null)).isInstanceOf(HttpServerErrorException.class);
    }

    /** An empty body is no page, not an empty audience: the latter would end the fan-out as complete. */
    @Test
    void page_aSuccessWithNoPage_propagates() {
        server.expect(requestTo(AUDIENCE_URI + "?userId=7&scope=FOLLOWERS&size=500")).andRespond(withSuccess(
                "{\"success\":true}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.page(7L, "FOLLOWERS", null)).isInstanceOf(IllegalStateException.class);
    }
}
