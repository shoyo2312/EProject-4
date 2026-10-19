package com.tiktok.notificationservice.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class VideoOwnerClientTest {

    private static final String POLICY_URI = "http://video/api/v1/videos/7/policy";
    private static final String OWNER_50 = "{\"success\":true,\"data\":{\"userId\":50}}";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://video");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final VideoOwnerClient client = new VideoOwnerClient(builder.build());

    /** An owner never changes, so a busy video's likes ask video-service once, not once each. */
    @Test
    void ownerOf_asksOncePerVideo() {
        server.expect(ExpectedCount.once(), requestTo(POLICY_URI))
                .andRespond(withSuccess(OWNER_50, MediaType.APPLICATION_JSON));

        assertThat(client.ownerOf("7")).isEqualTo(50L);
        assertThat(client.ownerOf("7")).isEqualTo(50L);
        server.verify();
    }

    /** A failure is not remembered: the next like asks again once video-service is back. */
    @Test
    void ownerOf_doesNotRememberAFailure() {
        server.expect(requestTo(POLICY_URI)).andRespond(withServerError());
        server.expect(requestTo(POLICY_URI)).andRespond(withSuccess(OWNER_50, MediaType.APPLICATION_JSON));

        assertThat(client.ownerOf("7")).isNull();
        assertThat(client.ownerOf("7")).isEqualTo(50L);
        server.verify();
    }
}
