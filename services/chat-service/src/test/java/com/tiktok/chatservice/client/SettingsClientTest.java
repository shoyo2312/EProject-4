package com.tiktok.chatservice.client;

import com.tiktok.chatservice.exception.DmSettingsUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SettingsClientTest {

    private static final String URL = "http://user-service/api/v1/users/internal/settings?ids=2";

    private MockRestServiceServer server;
    private SettingsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SettingsClient(builder, "http://user-service");
    }

    @Test
    void audience_isReadOffTheInternalBatchEndpoint() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"success\":true,\"data\":[{\"userId\":2,\"dmAudience\":\"FRIENDS\"}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.dmAudience(2L)).isEqualTo(DmAudience.FRIENDS);
    }

    /** Fails closed: an audience that cannot be read is not assumed to be EVERYONE. */
    @Test
    void userServiceDown_isAnErrorRatherThanAPermissiveDefault() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.dmAudience(2L))
                .isInstanceOf(DmSettingsUnavailableException.class);
    }

    /**
     * The endpoint answers every id it is asked for, defaults included, so an empty list is a
     * fault rather than "this account has no settings".
     */
    @Test
    void anEmptyAnswer_isAFaultNotAnAbsentSetting() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"success\":true,\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.dmAudience(2L))
                .isInstanceOf(DmSettingsUnavailableException.class);
    }

    /** A user-service deployed ahead of this one. Refused rather than guessed at. */
    @Test
    void anAudienceItCannotName_isRefused() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"success\":true,\"data\":[{\"userId\":2,\"dmAudience\":\"MUTUALS\"}]}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.dmAudience(2L))
                .isInstanceOf(DmSettingsUnavailableException.class);
    }
}
