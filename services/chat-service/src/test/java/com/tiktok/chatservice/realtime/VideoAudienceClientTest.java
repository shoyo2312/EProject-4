package com.tiktok.chatservice.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class VideoAudienceClientTest {

    private static final String URL = "http://video-service/api/v1/videos/100";

    private MockRestServiceServer server;
    private VideoAudienceClient client;
    private Instant now = Instant.parse("2026-09-23T10:00:00Z");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> now);
        client = new VideoAudienceClient(builder, "http://video-service", clock);
    }

    private static String video(String status, String visibility, boolean commentsDisabled) {
        return """
                {"success":true,"data":{"id":"100","status":"%s","visibility":"%s","commentsDisabled":%s}}"""
                .formatted(status, visibility, commentsDisabled);
    }

    @Test
    void aPublicVideoWithCommentsOnIsOpen() {
        server.expect(requestTo(URL)).andRespond(
                withSuccess(video("PUBLISHED", "PUBLIC", false), MediaType.APPLICATION_JSON));

        assertThat(client.commentsArePublic("100")).isTrue();
    }

    /** What an anonymous read answers for a PRIVATE or FRIENDS video, and for one that is gone. */
    @Test
    void aVideoAnonymousViewersCannotSeeIsClosed() {
        server.expect(requestTo(URL)).andRespond(withStatus(NOT_FOUND));

        assertThat(client.commentsArePublic("100")).isFalse();
    }

    @Test
    void commentsSwitchedOffAreClosed() {
        server.expect(requestTo(URL)).andRespond(
                withSuccess(video("PUBLISHED", "PUBLIC", true), MediaType.APPLICATION_JSON));

        assertThat(client.commentsArePublic("100")).isFalse();
    }

    /**
     * An account that accepts comments from no one closes every video it owns, whatever the
     * video's own switch says — video-service reports the two apart.
     */
    @Test
    void anAccountThatAcceptsNoCommentsIsClosed() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"id\":\"100\",\"status\":\"PUBLISHED\",\"visibility\":\"PUBLIC\","
                        + "\"commentsDisabled\":false,\"accountCommentsOff\":true}}",
                MediaType.APPLICATION_JSON));

        assertThat(client.commentsArePublic("100")).isFalse();
    }

    /** A private account's PUBLIC video is for its followers, and the topic is for anyone. */
    @Test
    void aPrivateAccountsVideoIsClosedEvenIfVideoServiceServesIt() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"id\":\"100\",\"status\":\"PUBLISHED\",\"visibility\":\"PUBLIC\","
                        + "\"commentsDisabled\":false,\"ownerPrivate\":true}}",
                MediaType.APPLICATION_JSON));

        assertThat(client.commentsArePublic("100")).isFalse();
    }

    @Test
    void aNonPublicAnswerIsClosedEvenIfVideoServiceServesIt() {
        server.expect(requestTo(URL)).andRespond(
                withSuccess(video("PUBLISHED", "FRIENDS", false), MediaType.APPLICATION_JSON));

        assertThat(client.commentsArePublic("100")).isFalse();
    }

    /** Asked as nobody: a viewer's own token would open videos only that viewer may see. */
    @Test
    void asksWithoutAToken() {
        server.expect(requestTo(URL))
                .andExpect(request -> assertThat(request.getHeaders().containsKey("Authorization")).isFalse())
                .andRespond(withSuccess(video("PUBLISHED", "PUBLIC", false), MediaType.APPLICATION_JSON));

        client.commentsArePublic("100");
        server.verify();
    }

    /** Fails closed, and does not remember the failure, so frames resume as soon as it answers. */
    @Test
    void anUnreachableVideoServiceClosesWithoutBeingRemembered() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(
                withSuccess(video("PUBLISHED", "PUBLIC", false), MediaType.APPLICATION_JSON));

        assertThat(client.commentsArePublic("100")).isFalse();
        assertThat(client.commentsArePublic("100")).isTrue();
        server.verify();
    }

    @Test
    void anAnswerIsReusedUntilItExpires() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(
                withSuccess(video("PUBLISHED", "PUBLIC", false), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(NOT_FOUND));

        assertThat(client.commentsArePublic("100")).isTrue();
        now = now.plus(VideoAudienceClient.TTL).minusSeconds(1);
        assertThat(client.commentsArePublic("100")).isTrue();
        now = now.plusSeconds(1);
        assertThat(client.commentsArePublic("100")).isFalse();
        server.verify();
    }

    @Test
    void aForgottenVideoIsAskedAgain() {
        server.expect(requestTo(URL)).andRespond(
                withSuccess(video("PUBLISHED", "PUBLIC", false), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withStatus(NOT_FOUND));

        assertThat(client.commentsArePublic("100")).isTrue();
        client.forget("100");
        assertThat(client.commentsArePublic("100")).isFalse();
        server.verify();
    }
}
