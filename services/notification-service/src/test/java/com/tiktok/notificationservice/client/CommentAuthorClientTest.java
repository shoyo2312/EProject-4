package com.tiktok.notificationservice.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CommentAuthorClientTest {

    private static final String AUTHOR_URI =
            "http://interaction/api/v1/interactions/internal/videos/123/comments/9/author";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://interaction");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CommentAuthorClient client = new CommentAuthorClient(builder.build());

    @Test
    void authorOf_readsTheWriterOffInteractionService() {
        server.expect(requestTo(AUTHOR_URI)).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"videoId\":123,\"commentId\":9,\"userId\":50}}",
                MediaType.APPLICATION_JSON));

        assertThat(client.authorOf("123:9")).isEqualTo(50L);
    }

    /**
     * What CommentNotFoundException becomes through BaseExceptionHandler. No retry can make a
     * comment exist, so this answers nobody rather than throwing.
     */
    @Test
    void authorOf_aCommentInteractionServiceNeverStored_isNull() {
        server.expect(requestTo(AUTHOR_URI)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"code\":\"COMMENT_NOT_FOUND\",\"message\":\"Comment not found: 9\","
                        + "\"timestamp\":\"2026-10-22T12:00:00Z\"}"));

        assertThat(client.authorOf("123:9")).isNull();
        server.verify();
    }

    static Stream<Arguments> notFoundsThatSayNothingAboutTheComment() {
        return Stream.of(
                // An INTERACTION_SERVICE_URI that lands on another service of ours, or a path that
                // moved in a deploy: an unmapped path is answered by BaseExceptionHandler with the
                // status name as the code.
                Arguments.of("another service's own 404", withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"code\":\"NOT_FOUND\","
                                + "\"message\":\"No static resource api/v1/interactions/internal/videos/123/"
                                + "comments/9/author.\",\"timestamp\":\"2026-10-22T12:00:00Z\"}")),
                Arguments.of("a 404 with no body", withResourceNotFound()),
                Arguments.of("a proxy's HTML 404", withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.TEXT_HTML).body("<html><body>404 Not Found</body></html>")),
                Arguments.of("a 404 whose JSON is cut short", withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"success\":false,\"code\":\"COMMENT_NOT")));
    }

    /**
     * A null drops the warning for good, so a 404 only counts as "no such comment" when
     * interaction-service itself says so. Any other 404 — a wrong INTERACTION_SERVICE_URI, a proxy,
     * a body that cannot be read — must throw, or every warning over a comment would be lost
     * without a trace.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("notFoundsThatSayNothingAboutTheComment")
    void authorOf_aNotFoundThatIsNotInteractionServices_propagates(String what, ResponseCreator answer) {
        server.expect(requestTo(AUTHOR_URI)).andRespond(answer);

        assertThatThrownBy(() -> client.authorOf("123:9")).isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    /**
     * An outage is thrown, unlike VideoOwnerClient: the event is then redelivered by kafka-lib and
     * dead-lettered past its retries, instead of the warning being dropped for good.
     */
    @Test
    void authorOf_interactionServiceDown_throwsSoTheEventIsRetried() {
        server.expect(requestTo(AUTHOR_URI)).andRespond(withServerError());

        assertThatThrownBy(() -> client.authorOf("123:9")).isInstanceOf(RestClientException.class);
    }

    @Test
    void authorOf_anUnreadableTarget_isNullWithoutACall() {
        assertThat(client.authorOf("9")).isNull();
        assertThat(client.authorOf("abc:9")).isNull();
        assertThat(client.authorOf("1:2:3")).isNull();
        assertThat(client.authorOf(null)).isNull();
        server.verify();
    }
}
