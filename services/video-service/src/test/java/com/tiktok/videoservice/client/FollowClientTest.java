package com.tiktok.videoservice.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FollowClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://user-service");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final FollowClient client = new FollowClient(builder.build());

    @Test
    void followedAmong_asksOnceAndAnswersTheSubset() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/following?followerId=1&ids=7&ids=8"))
                .andRespond(withSuccess("""
                        {"success": true, "data": [8]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.followedAmong(1L, List.of(7L, 8L))).containsExactly(8L);
        server.verify();
    }

    /** Fails closed: an owner nobody could vouch for is one the viewer does not follow. */
    @Test
    void followedAmong_whenUserServiceFails_followsNobody() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/following?followerId=1&ids=7"))
                .andRespond(withServerError());

        assertThat(client.followedAmong(1L, List.of(7L))).isEmpty();
        server.verify();
    }

    @Test
    void followedAmong_forAnAnonymousViewer_asksNothing() {
        assertThat(client.followedAmong(null, List.of(7L))).isEmpty();
        server.verify();
    }

    /** user-service caps one batch at 100 ids, so a longer list goes in chunks rather than a 400. */
    @Test
    void followedAmong_overTheCap_asksInChunks() {
        List<Long> owners = LongStream.rangeClosed(1, 101).boxed().toList();
        server.expect(requestTo(startsWith("http://user-service/api/v1/users/internal/following?followerId=500&ids=1&")))
                .andRespond(withSuccess("""
                        {"success": true, "data": [1]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://user-service/api/v1/users/internal/following?followerId=500&ids=101"))
                .andRespond(withSuccess("""
                        {"success": true, "data": [101]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.followedAmong(500L, owners)).containsExactlyInAnyOrder(1L, 101L);
        server.verify();
    }
}
