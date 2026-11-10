package com.tiktok.notificationservice.client;

import com.tiktok.notificationservice.client.StoryAnnouncementClient.Announcement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
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

    /**
     * What StoryNotFoundException becomes through BaseExceptionHandler, for a story that is deleted,
     * expired, not live, or owned by a hidden account.
     */
    @Test
    void announcement_aStoryThatIsGone_isEmpty() {
        server.expect(requestTo(ANNOUNCEMENT_URI)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"code\":\"STORY_NOT_FOUND\",\"message\":\"Story not found: s-1\","
                        + "\"timestamp\":\"2026-10-22T13:00:00Z\"}"));

        assertThat(client.announcement("s-1")).isEmpty();
        server.verify();
    }

    static Stream<Arguments> notFoundsThatSayNothingAboutTheStory() {
        return Stream.of(
                // A STORY_SERVICE_URI that lands on another service of ours: its unmapped path is
                // answered by BaseExceptionHandler with the status name as the code.
                Arguments.of("another service's own 404", withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"code\":\"NOT_FOUND\","
                                + "\"message\":\"No static resource api/v1/stories/internal/s-1/announcement.\","
                                + "\"timestamp\":\"2026-10-22T13:00:00Z\"}")),
                Arguments.of("a 404 with no body", withResourceNotFound()),
                Arguments.of("a proxy's HTML 404", withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.TEXT_HTML).body("<html><body>404 Not Found</body></html>")),
                Arguments.of("a 404 whose JSON is cut short", withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"success\":false,\"code\":\"STORY_NOT")));
    }

    /**
     * An empty answer ends the event as handled, so a 404 only counts as "gone" when story-service
     * itself says so. Any other 404 — a wrong STORY_SERVICE_URI, a proxy, a body that cannot be read —
     * must throw, or every announcement would be dropped without a trace.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("notFoundsThatSayNothingAboutTheStory")
    void announcement_aNotFoundThatIsNotStoryServices_propagates(String what, ResponseCreator answer) {
        server.expect(requestTo(ANNOUNCEMENT_URI)).andRespond(answer);

        assertThatThrownBy(() -> client.announcement("s-1")).isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    /**
     * Not BlockClient's fail-open: an empty answer here means "nothing to announce" and the event
     * is then treated as done, so a story-service that merely failed to answer must throw for the
     * delivery to be retried.
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
