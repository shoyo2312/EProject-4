package com.tiktok.notificationservice.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BlockClientTest {

    private static final String URI = "http://user/api/v1/users/internal/blocks?userA=1&userB=2";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://user");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final BlockClient client = new BlockClient(builder.build());

    @Test
    void isBlockedBetween_readsTheAnswer() {
        server.expect(requestTo(URI)).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"blocked\":true}}", MediaType.APPLICATION_JSON));

        assertThat(client.isBlockedBetween(1L, 2L)).isTrue();
        server.verify();
    }

    /**
     * A notification carries the blocked account's words — a comment body goes into the push
     * verbatim — so an unanswered check must not read as "not blocked". It throws, and the
     * consumer's claim is released for kafka-lib to redeliver.
     */
    @Test
    void isBlockedBetween_unanswered_throwsInsteadOfNotifying() {
        server.expect(requestTo(URI)).andRespond(withServerError());

        assertThatThrownBy(() -> client.isBlockedBetween(1L, 2L)).isInstanceOf(RestClientException.class);
    }
}
