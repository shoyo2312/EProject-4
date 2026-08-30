package com.tiktok.interactionservice.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class VideoOwnershipClientTest {

    private RestClient.Builder builder = RestClient.builder().baseUrl("http://video-service");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final VideoOwnershipClient client = new VideoOwnershipClient(builder.build());

    @Test
    void isOwnedBy_true_whenVideoServiceNamesTheSameOwner() {
        server.expect(requestTo("http://video-service/api/v1/videos/42/policy"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"userId": 7, "title": "ignored"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.isOwnedBy(42L, 7L)).isTrue();
    }

    @Test
    void isOwnedBy_false_whenTheOwnerIsSomeoneElse() {
        server.expect(requestTo("http://video-service/api/v1/videos/42/policy"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"userId": 7}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.isOwnedBy(42L, 8L)).isFalse();
    }

    /**
     * /policy, never /videos/{id}: the video endpoint filters by visibility and this client sends
     * no token, so a PRIVATE or still-PROCESSING video answered 404 — denying its owner the right
     * to delete comments on it, and failing open on comments they had switched off.
     */
    @Test
    void areCommentsDisabled_readsThePolicyOfAVideoTheAnonymousCallerCannotSee() {
        server.expect(requestTo("http://video-service/api/v1/videos/42/policy"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"userId": 7, "commentsDisabled": true}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.areCommentsDisabled(42L)).isTrue();
    }

    @Test
    void isOwnedBy_false_whenVideoServiceIsUnreachable() {
        server.expect(requestTo("http://video-service/api/v1/videos/42/policy"))
                .andRespond(withServerError());

        assertThat(client.isOwnedBy(42L, 7L)).isFalse();
    }
}
