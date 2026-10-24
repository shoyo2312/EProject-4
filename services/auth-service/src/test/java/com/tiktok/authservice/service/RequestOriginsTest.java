package com.tiktok.authservice.service;

import com.tiktok.common.http.InternalApiToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one request says about where it came from. Every field but the device id was read by the
 * recorder before this existed; the cases that matter are the ones a client can write.
 */
class RequestOriginsTest {

    private final InternalApiToken token = InternalApiToken.from(testProfile());
    private final GeoIpLocator locator = new GeoIpLocator("src/test/resources/geoip/GeoIP2-City-Test.mmdb");
    private final RequestOrigins origins = new RequestOrigins(token, locator);

    private static MockEnvironment testProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return environment;
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        locator.close();
    }

    private static void arrive(Map<String, String> headers) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        headers.forEach(request::addHeader);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** Anything that reaches this port directly can write X-Client-Ip; only the token says it is the gateway. */
    @Test
    void theGatewaysAddressCountsOnlyBesideTheInternalToken() {
        arrive(Map.of("X-Client-Ip", "81.2.69.160", InternalApiToken.HEADER, token.value()));
        assertThat(origins.current())
                .extracting(RequestOrigin::address, RequestOrigin::location)
                .containsExactly("81.2.69.160", "London, United Kingdom");

        arrive(Map.of("X-Client-Ip", "81.2.69.160"));
        assertThat(origins.current())
                .extracting(RequestOrigin::address, RequestOrigin::location)
                .containsExactly("127.0.0.1", null);
    }

    @Test
    void aDeviceIdThatFitsIsRead() {
        arrive(Map.of("X-Device-Id", "test-device-aaaaaaaa"));

        assertThat(origins.current().deviceId()).isEqualTo("test-device-aaaaaaaa");
    }

    /** The id is the client's to write; anything outside the format is treated as no id at all. */
    @Test
    void aHostileDeviceIdIsAbsent() {
        for (String id : List.of("", "short", "x".repeat(65), "has space aaaaaaaaaaa",
                "semi;colon-aaaaaaaaaaa", "ünïcødé-aaaaaaaaaaaa")) {
            arrive(Map.of("X-Device-Id", id));
            assertThat(origins.current().deviceId()).as(id).isNull();
        }
    }

    /** RFC 8941 strings arrive quoted, and every desktop Chromium answers the model hint with "". */
    @Test
    void clientHintsAreUnquoted() {
        arrive(Map.of("Sec-CH-UA-Model", "\"Pixel 9\"", "Sec-CH-UA-Platform-Version", "\"\""));

        assertThat(origins.current())
                .extracting(RequestOrigin::model, RequestOrigin::platformVersion)
                .containsExactly("Pixel 9", null);
    }

    /** A Kafka consumer or a scheduled sweep acting on an account. */
    @Test
    void offARequestThreadNothingIsKnown() {
        RequestContextHolder.resetRequestAttributes();

        assertThat(origins.current()).isEqualTo(RequestOrigin.NONE);
    }
}
