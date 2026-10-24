package com.tiktok.notificationservice.client;

import com.tiktok.notificationservice.service.PushPreferences;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PushPreferencesClientTest {

    private static final String SETTINGS_URI = "http://user/api/v1/users/internal/settings?ids=7";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://user");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final PushPreferencesClient client = new PushPreferencesClient(builder.build());

    private void answer(String policy) {
        server.expect(requestTo(SETTINGS_URI)).andRespond(withSuccess(
                "{\"success\":true,\"data\":[" + policy + "]}", MediaType.APPLICATION_JSON));
    }

    @Test
    void parsesTheSwitchesAndSleepHours() {
        answer("{\"userId\":7,\"commentAudience\":\"EVERYONE\",\"pushLikes\":false,\"pushComments\":true,"
                + "\"pushNewFollowers\":false,\"sleepHoursStart\":\"23:30\",\"sleepHoursEnd\":\"07:30\","
                + "\"timeZone\":\"Asia/Ho_Chi_Minh\",\"forYouKeywords\":[]}");

        assertThat(client.preferencesOf(7L)).isEqualTo(new PushPreferences(false, true, false,
                LocalTime.of(23, 30), LocalTime.of(7, 30), ZoneId.of("Asia/Ho_Chi_Minh")));
        server.verify();
    }

    /** A user-service from before the switches: everything was on then. */
    @Test
    void missingSwitchesReadAsOn() {
        answer("{\"userId\":7}");

        assertThat(client.preferencesOf(7L)).isEqualTo(new PushPreferences(true, true, true, null, null, null));
    }

    @Test
    void anUnknownZoneMutesNothing() {
        answer("{\"userId\":7,\"sleepHoursStart\":\"23:30\",\"sleepHoursEnd\":\"07:30\",\"timeZone\":\"Mars/Base\"}");

        assertThat(client.preferencesOf(7L).zone()).isNull();
    }

    @Test
    void serverErrorIsNull() {
        server.expect(requestTo(SETTINGS_URI)).andRespond(withServerError());

        assertThat(client.preferencesOf(7L)).isNull();
    }

    /** user-service answers every id it is asked for; an answer without this one is no answer. */
    @Test
    void anAnswerWithoutTheAccountIsNull() {
        server.expect(requestTo(SETTINGS_URI)).andRespond(withSuccess(
                "{\"success\":true,\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.preferencesOf(7L)).isNull();
    }
}
