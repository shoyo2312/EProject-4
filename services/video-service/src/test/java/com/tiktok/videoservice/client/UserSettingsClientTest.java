package com.tiktok.videoservice.client;

import com.tiktok.videoservice.service.UserSettingsCache;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The two things that would break quietly: a cache that is read but never written (every
 * {@code /policy} read then pays a call to user-service), and a failure that answers something
 * other than "no restriction" — this guard has to fail open, unlike chat-service's.
 */
class UserSettingsClientTest {

    private static final String ONE_POLICY = """
            {"success": true, "data": [{"userId": 7, "commentAudience": "NO_ONE"}]}
            """;

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://user-service");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final UserSettingsCache cache = mock(UserSettingsCache.class);
    private final UserSettingsClient client = new UserSettingsClient(builder.build(), cache);

    @Test
    void commentAudience_onACacheHit_neverCallsUserService() {
        when(cache.getCommentAudience(7L)).thenReturn(Optional.of("FRIENDS"));

        assertThat(client.commentAudience(7L)).isEqualTo("FRIENDS");

        server.verify();
    }

    @Test
    void commentAudience_onAMiss_readsUserServiceAndPopulatesTheCache() {
        when(cache.getCommentAudience(7L)).thenReturn(Optional.empty());
        server.expect(requestTo("http://user-service/api/v1/users/internal/settings?ids=7"))
                .andRespond(withSuccess(ONE_POLICY, MediaType.APPLICATION_JSON));

        assertThat(client.commentAudience(7L)).isEqualTo("NO_ONE");

        verify(cache).putCommentAudience(7L, "NO_ONE");
        server.verify();
    }

    @Test
    void commentAudience_whenUserServiceFails_answersNothingAndCachesNothing() {
        when(cache.getCommentAudience(7L)).thenReturn(Optional.empty());
        server.expect(requestTo("http://user-service/api/v1/users/internal/settings?ids=7"))
                .andRespond(withServerError());

        assertThat(client.commentAudience(7L)).isNull();

        verify(cache).getCommentAudience(7L);
        server.verify();
    }

    /** No owner, no question to ask — an unmapped video should not cost a round trip. */
    @Test
    void commentAudience_withNoUserId_asksNobody() {
        assertThat(client.commentAudience(null)).isNull();

        verifyNoInteractions(cache);
        server.verify();
    }
}
