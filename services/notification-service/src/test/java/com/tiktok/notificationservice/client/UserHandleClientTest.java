package com.tiktok.notificationservice.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UserHandleClientTest {

    private static final String URI =
            "http://user/api/v1/users/internal/ids-by-username?usernames=alice&usernames=bob";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://user");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final UserHandleClient client = new UserHandleClient(builder.build());

    @Test
    void idsByUsername_readsTheMap() {
        server.expect(requestTo(URI)).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"alice\":1}}", MediaType.APPLICATION_JSON));

        assertThat(client.idsByUsername(List.of("alice", "bob"))).isEqualTo(Map.of("alice", 1L));
        server.verify();
    }

    /** Unanswered is not "nobody was tagged": it throws, and kafka-lib redelivers. */
    @Test
    void idsByUsername_unanswered_throws() {
        server.expect(requestTo(URI)).andRespond(withServerError());

        assertThatThrownBy(() -> client.idsByUsername(List.of("alice", "bob")))
                .isInstanceOf(RestClientException.class);
    }
}
