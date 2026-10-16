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

    /** An account open to no one closes the thread like the switch does, so the listing hides it. */
    @Test
    void areCommentsDisabled_whenTheOwnerAcceptsCommentsFromNoOne() {
        server.expect(requestTo("http://video-service/api/v1/videos/43/policy"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"userId": 7, "commentsDisabled": false, "commentAudience": "NO_ONE"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.areCommentsDisabled(43L)).isTrue();
    }

    /** One read answers the whole guard — the switch, the owner and the audience. */
    @Test
    void commentPolicy_readsAllThreeQuestionsFromOneCall() {
        server.expect(requestTo("http://video-service/api/v1/videos/42/policy"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"userId": 7, "commentsDisabled": false,
                         "commentAudience": "FRIENDS"}}
                        """, MediaType.APPLICATION_JSON));

        CommentPolicy policy = client.commentPolicy(42L);

        assertThat(policy.ownerId()).isEqualTo(7L);
        assertThat(policy.commentsDisabled()).isFalse();
        assertThat(policy.commentAudience()).isEqualTo(CommentAudience.FRIENDS);
    }

    /**
     * A user-service deployed ahead of this one, or a video-service that could not reach it at
     * all: an audience nobody can name leaves commenting open rather than failing the request.
     */
    @Test
    void commentPolicy_withAnAudienceItCannotName_failsOpen() {
        server.expect(requestTo("http://video-service/api/v1/videos/42/policy"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"userId": 7, "commentAudience": "MUTUALS"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.commentPolicy(42L).commentAudience()).isEqualTo(CommentAudience.EVERYONE);
    }

    @Test
    void commentPolicy_whenVideoServiceIsDown_failsOpenWithNoOwner() {
        server.expect(requestTo("http://video-service/api/v1/videos/42/policy"))
                .andRespond(withServerError());

        CommentPolicy policy = client.commentPolicy(42L);

        assertThat(policy.ownerId()).isNull();
        assertThat(policy.commentsDisabled()).isFalse();
        assertThat(policy.commentAudience()).isEqualTo(CommentAudience.EVERYONE);
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

    /** The watch path takes its denominator from here rather than asking /policy a second time. */
    @Test
    void requireVisible_answersTheProbedDurationOfTheVideoItRead() {
        server.expect(requestTo("http://video-service/api/v1/videos/42"))
                .andRespond(withSuccess("{\"success\": true, \"data\": {\"id\": \"42\", \"durationSeconds\": 61}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.requireVisible(42L)).isEqualTo(61_000L);
        server.verify();
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
    /** The owner's comment filters ride on the same /policy read. */
    @Test
    void commentPolicy_carriesTheOwnersCommentFilters() {
        server.expect(requestTo("http://video-service/api/v1/videos/42/policy"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"userId": 7, "commentAudience": "EVERYONE",
                          "filterSpamOffensive": false, "filterAllComments": true, "commentKeywords": ["spoiler"]}}
                        """, MediaType.APPLICATION_JSON));

        CommentPolicy policy = client.commentPolicy(42L);

        assertThat(policy.filterSpamOffensive()).isFalse();
        assertThat(policy.filterAllComments()).isTrue();
        assertThat(policy.commentKeywords()).containsExactly("spoiler");
    }

    /**
     * Unknown filters — user-service unreachable, or a video-service older than them — are their
     * defaults: spam and offence held, nothing else. Holding every comment would be failing closed.
     */
    @Test
    void commentPolicy_withoutFilters_readsTheirDefaults() {
        server.expect(requestTo("http://video-service/api/v1/videos/42/policy"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"userId": 7, "commentAudience": null}}
                        """, MediaType.APPLICATION_JSON));

        CommentPolicy policy = client.commentPolicy(42L);

        assertThat(policy.filterSpamOffensive()).isTrue();
        assertThat(policy.filterAllComments()).isFalse();
        assertThat(policy.commentKeywords()).isEmpty();
    }
}
