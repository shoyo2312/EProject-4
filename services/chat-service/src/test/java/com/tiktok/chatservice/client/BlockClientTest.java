package com.tiktok.chatservice.client;

import com.tiktok.chatservice.exception.BlockCheckUnavailableException;
import com.tiktok.chatservice.exception.MessagingBlockedException;
import com.tiktok.common.http.InternalApiToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BlockClientTest {

    private static final String URL = "http://user-service/api/v1/users/internal/blocks?userA=1&userB=2";
    private static final InternalApiToken TOKEN = InternalApiToken.from(new MockEnvironment()
            .withProperty("INTERNAL_API_TOKEN", "internal-token-for-tests-0123456789abcdef"));

    private MockRestServiceServer server;
    private BlockClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BlockClient(builder, "http://user-service", TOKEN);
    }

    @Test
    void blockedPair_isRefused() {
        server.expect(requestTo(URL)).andExpect(header(InternalApiToken.HEADER, TOKEN.value())).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"blocked\":true}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.requireNotBlocked(1L, 2L)).isInstanceOf(MessagingBlockedException.class);
    }

    @Test
    void unblockedPair_passes() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"blocked\":false}}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> client.requireNotBlocked(1L, 2L)).doesNotThrowAnyException();
    }

    /** Fails closed: a block that cannot be checked is not assumed absent. */
    @Test
    void userServiceDown_isAnErrorRatherThanAPass() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.requireNotBlocked(1L, 2L)).isInstanceOf(BlockCheckUnavailableException.class);
    }

    private static final String STATES_URL = "http://user-service/api/v1/users/internal/blocks/states?viewerId=1&otherIds=2,3";

    @Test
    void statesOf_readsOneBatchWithInternalToken() {
        server.expect(requestTo(STATES_URL)).andExpect(header(InternalApiToken.HEADER, TOKEN.value()))
                .andRespond(withSuccess("""
                        {"success":true,"data":[
                          {"userId":2,"blockedByViewer":false,"blockedViewer":true,"version":123},
                          {"userId":3,"blockedByViewer":false,"blockedViewer":false,"version":0}]}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.statesOf(1L, List.of(2L, 3L, 2L)))
                .containsEntry(2L, new BlockClient.BlockState(false, true, 123))
                .containsEntry(3L, new BlockClient.BlockState(false, false, 0));
        server.verify();
    }

    /**
     * user-service answers at most 100 ids a call and refuses more; the whole inbox's states used
     * to go in one call, so past 100 conversations every block state was lost.
     */
    @Test
    void statesOf_pastAHundredIds_asksInBatchesOfAHundred() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(2, 151).boxed().toList();
        for (List<Long> batch : List.of(ids.subList(0, 100), ids.subList(100, 150))) {
            String query = batch.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            String data = batch.stream()
                    .map(id -> "{\"userId\":" + id + ",\"blockedByViewer\":" + (id == 150L)
                            + ",\"blockedViewer\":false,\"version\":1}")
                    .collect(java.util.stream.Collectors.joining(","));
            server.expect(requestTo("http://user-service/api/v1/users/internal/blocks/states?viewerId=1&otherIds=" + query))
                    .andRespond(withSuccess("{\"success\":true,\"data\":[" + data + "]}", MediaType.APPLICATION_JSON));
        }

        var states = client.statesOf(1L, ids);

        assertThat(states).hasSize(150);
        assertThat(states.get(150L).blockedByViewer()).isTrue();
        server.verify();
    }

    @Test
    void statesOf_outageIsUnknown() {
        server.expect(requestTo(STATES_URL)).andRespond(withServerError());
        assertThat(client.statesOf(1L, List.of(2L, 3L))).isEmpty();
    }

    @Test
    void statesOf_malformedOrPartialResponseIsUnknown() {
        server.expect(requestTo(STATES_URL)).andRespond(withSuccess("""
                {"success":true,"data":[{"userId":2,"blockedByViewer":true}]}
                """, MediaType.APPLICATION_JSON));
        assertThat(client.statesOf(1L, List.of(2L, 3L))).isEmpty();
    }

    @Test
    void statesOf_invalidJsonIsUnknown() {
        server.expect(requestTo(STATES_URL)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        assertThat(client.statesOf(1L, List.of(2L, 3L))).isEmpty();
    }

    @Test
    void statesOf_emptyListMakesNoRequest() {
        assertThat(client.statesOf(1L, List.of())).isEmpty();
        server.verify();
    }
}
