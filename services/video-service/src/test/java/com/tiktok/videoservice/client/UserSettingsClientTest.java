package com.tiktok.videoservice.client;

import com.tiktok.videoservice.service.UserSettingsCache;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
            {"success": true, "data": [{"userId": 7, "commentAudience": "NO_ONE", "dmAudience": "EVERYONE",
              "filterSpamOffensive": false, "filterAllComments": true, "commentKeywords": ["spoiler"]}]}
            """;

    private static final OwnerCommentSettings SEVEN =
            new OwnerCommentSettings("NO_ONE", false, true, List.of("spoiler"));

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://user-service");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final UserSettingsCache cache = mock(UserSettingsCache.class);
    private final UserSettingsClient client = new UserSettingsClient(builder.build(), cache);

    @Test
    void commentSettings_onACacheHit_neverCallUserService() {
        OwnerCommentSettings cached = new OwnerCommentSettings("FRIENDS", true, false, List.of());
        when(cache.get(7L)).thenReturn(Optional.of(cached));

        assertThat(client.commentSettings(7L)).isEqualTo(cached);

        server.verify();
    }

    @Test
    void commentSettings_onAMiss_readUserService_filtersIncluded_andPopulateTheCache() {
        when(cache.get(7L)).thenReturn(Optional.empty());
        server.expect(requestTo("http://user-service/api/v1/users/internal/settings?ids=7"))
                .andRespond(withSuccess(ONE_POLICY, MediaType.APPLICATION_JSON));

        assertThat(client.commentSettings(7L)).isEqualTo(SEVEN);

        verify(cache).putAll(Map.of(7L, SEVEN));
        server.verify();
    }

    /** A user-service from before the filters: relayed as unknown, not as a default made up here. */
    @Test
    void anOlderUserService_withoutTheFilters_relaysThemAsUnknown() {
        when(cache.get(7L)).thenReturn(Optional.empty());
        server.expect(requestTo("http://user-service/api/v1/users/internal/settings?ids=7"))
                .andRespond(withSuccess("""
                        {"success": true, "data": [{"userId": 7, "commentAudience": "EVERYONE"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.commentSettings(7L)).isEqualTo(new OwnerCommentSettings("EVERYONE", null, null, null));
    }

    @Test
    void commentSettings_whenUserServiceFails_answerNothingAndCacheNothing() {
        when(cache.get(7L)).thenReturn(Optional.empty());
        server.expect(requestTo("http://user-service/api/v1/users/internal/settings?ids=7"))
                .andRespond(withServerError());

        assertThat(client.commentSettings(7L)).isNull();

        verify(cache).get(7L);
        server.verify();
    }

    /**
     * A feed page asks about every owner on it at once: cache hits answer themselves, and only
     * the misses go to user-service — in one call, not one per owner.
     */
    @Test
    void closedToComments_asksUserServiceOnceForTheMissesOnly() {
        // One MGET for the page: 7 misses, 8 and 9 answer from the cache.
        when(cache.getAll(Set.of(7L, 8L, 9L))).thenReturn(Map.of(
                8L, new OwnerCommentSettings("EVERYONE", true, false, List.of()),
                9L, new OwnerCommentSettings("NO_ONE", true, false, List.of())));
        server.expect(requestTo("http://user-service/api/v1/users/internal/settings?ids=7"))
                .andRespond(withSuccess(ONE_POLICY, MediaType.APPLICATION_JSON));

        assertThat(client.closedToComments(List.of(7L, 8L, 9L, 9L))).containsExactlyInAnyOrder(7L, 9L);

        verify(cache).putAll(Map.of(7L, SEVEN));
        server.verify();
    }

    /** No owner, no question to ask — an unmapped video should not cost a round trip. */
    @Test
    void commentSettings_withNoUserId_askNobody() {
        assertThat(client.commentSettings(null)).isNull();

        verifyNoInteractions(cache);
        server.verify();
    }
}
