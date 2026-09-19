package com.tiktok.interactionservice.client;

import com.tiktok.interactionservice.exception.VideoNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
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

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void callerToken(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, header);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /**
     * Asked as the caller, with their token: video-service's own getById is what knows whether this
     * viewer may see a PRIVATE or FRIENDS video, and a 404 there is the answer for every video this
     * caller cannot see.
     */
    @Test
    void requireVisible_asksAsTheCallerAndRefusesWhatTheyCannotSee() {
        callerToken("Bearer abc");
        server.expect(requestTo("http://video-service/api/v1/videos/42"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer abc"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.requireVisible(42L)).isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void requireVisible_passesAVideoTheCallerCanSee() {
        server.expect(requestTo("http://video-service/api/v1/videos/42"))
                .andRespond(withSuccess("{\"success\": true, \"data\": {\"id\": \"42\"}}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> client.requireVisible(42L)).doesNotThrowAnyException();
    }

    /**
     * Fails open, like the comments switch: an outage of video-service should not stop every like
     * on the platform, and nothing here is a secret the check protects on its own — video-service
     * still refuses to serve the video itself.
     */
    @Test
    void requireVisible_letsTheInteractionThroughWhenVideoServiceIsDown() {
        server.expect(requestTo("http://video-service/api/v1/videos/42")).andRespond(withServerError());

        assertThatCode(() -> client.requireVisible(42L)).doesNotThrowAnyException();
    }

    @Test
    void isOwnedBy_false_whenVideoServiceIsUnreachable() {
        server.expect(requestTo("http://video-service/api/v1/videos/42/policy"))
                .andRespond(withServerError());

        assertThat(client.isOwnedBy(42L, 7L)).isFalse();
    }
}
