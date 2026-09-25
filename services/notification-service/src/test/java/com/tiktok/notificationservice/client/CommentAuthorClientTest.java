package com.tiktok.notificationservice.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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

    /** No retry can make a comment exist, so this answers nobody rather than throwing. */
    @Test
    void authorOf_aCommentInteractionServiceNeverStored_isNull() {
        server.expect(requestTo(AUTHOR_URI)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.authorOf("123:9")).isNull();
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
