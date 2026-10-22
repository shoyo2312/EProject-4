package com.tiktok.notificationservice.client;

import com.tiktok.notificationservice.client.StoryAnnouncementClient.Announcement;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StoryAnnouncementClientTest {

    private static final String ANNOUNCEMENT_URI = "http://story/api/v1/stories/internal/s-1/announcement";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://story");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final StoryAnnouncementClient client = new StoryAnnouncementClient(builder.build());

    @Test
    void announcement_aLiveStory_isItsOwnerAndCurrentVisibility() {
        server.expect(requestTo(ANNOUNCEMENT_URI)).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"userId\":7,\"visibility\":\"FRIENDS\"}}", MediaType.APPLICATION_JSON));

        assertThat(client.announcement("s-1")).contains(new Announcement(7L, "FRIENDS"));
        server.verify();
    }

    /** story-service's answer for a story that is deleted, expired, not live, or owned by a hidden account. */
    @Test
    void announcement_aStoryThatIsGone_isEmpty() {
        server.expect(requestTo(ANNOUNCEMENT_URI)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"code\":\"STORY_NOT_FOUND\",\"message\":\"Story not found\"}"));

        assertThat(client.announcement("s-1")).isEmpty();
        server.verify();
    }

    /**
     * Not BlockClient's fail-open: an empty answer here means "nothing to announce" and the event
     * is then treated as done, so a story-service that merely failed to answer must throw for the
     * delivery to be retried until the story is confirmed.
     */
    @Test
    void announcement_anyOtherFailure_propagates() {
        server.expect(requestTo(ANNOUNCEMENT_URI)).andRespond(withServerError());

        assertThatThrownBy(() -> client.announcement("s-1")).isInstanceOf(HttpServerErrorException.class);
    }

    /** A 200 that confirms nothing is a failure, not a story that is gone. */
    @Test
    void announcement_aSuccessWithNoAnnouncement_propagates() {
        server.expect(requestTo(ANNOUNCEMENT_URI)).andRespond(withSuccess(
                "{\"success\":true}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.announcement("s-1")).isInstanceOf(IllegalStateException.class);
    }
}
