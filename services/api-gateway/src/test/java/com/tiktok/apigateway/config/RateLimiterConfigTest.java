package com.tiktok.apigateway.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who a request is counted against. No Spring context: the resolver is a function of the
 * connection's peer, the header and the trusted ranges.
 */
class RateLimiterConfigTest {

    private static final RateLimitProperties PROPERTIES = new RateLimitProperties(
            List.of("127.0.0.0/8", "::1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"));

    private String keyFor(String peer, String... forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/api/v1/videos")
                .remoteAddress(new InetSocketAddress(peer, 40000));
        if (forwardedFor.length > 0) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return new RateLimiterConfig().ipKeyResolver(PROPERTIES)
                .resolve(MockServerWebExchange.from(request))
                .block();
    }

    @Test
    void behindTheWebProxy_theClientItSawIsTheKey() {
        assertThat(keyFor("10.0.0.5", "203.0.113.7")).isEqualTo("203.0.113.7");
    }

    /**
     * The first entry is whatever the client wrote; a proxy appends the address it actually saw.
     * Reading the first entry let a client rotate a made-up address per request and get a fresh
     * bucket each time.
     */
    @Test
    void entriesTheClientWrote_areNotBelieved() {
        assertThat(keyFor("10.0.0.5", "1.2.3.4, 203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(keyFor("10.0.0.5", "5.6.7.8, 203.0.113.7")).isEqualTo("203.0.113.7");
    }

    /** A chain of internal proxies is walked through to the first address that is not one. */
    @Test
    void severalTrustedHops_areWalkedThrough() {
        assertThat(keyFor("10.0.0.5", "9.9.9.9, 203.0.113.7, 192.168.1.20, 172.16.4.4"))
                .isEqualTo("203.0.113.7");
    }

    /** The same walk across two header lines, the other way a proxy may append. */
    @Test
    void repeatedHeaders_areReadInOrder() {
        assertThat(keyFor("10.0.0.5", "9.9.9.9", "203.0.113.7")).isEqualTo("203.0.113.7");
    }

    /** Reached directly, the header is the caller's own claim and is ignored. */
    @Test
    void fromAnUntrustedPeer_theHeaderIsIgnored() {
        assertThat(keyFor("198.51.100.9", "1.2.3.4")).isEqualTo("198.51.100.9");
    }

    @Test
    void withoutTheHeader_thePeerIsTheKey() {
        assertThat(keyFor("198.51.100.9")).isEqualTo("198.51.100.9");
        assertThat(keyFor("10.0.0.5")).isEqualTo("10.0.0.5");
    }

    /**
     * Not an address, so no proxy wrote it: the walk stops at the proxy that passed it on. A
     * hostname in particular must not be resolved, since the client chose it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"attacker.example.com", "not-an-ip", "1.2.3.4:5678", "unknown"})
    void aHopThatIsNotAnAddress_endsTheWalk(String garbage) {
        assertThat(keyFor("10.0.0.5", garbage)).isEqualTo("10.0.0.5");
        assertThat(keyFor("10.0.0.5", "203.0.113.7, " + garbage)).isEqualTo("10.0.0.5");
    }

    @Test
    void ipv6_isMatchedAndReturned() {
        assertThat(keyFor("fd00::1", "2001:db8::42")).isEqualTo("2001:db8:0:0:0:0:0:42");
        assertThat(keyFor("2001:db8::99", "2001:db8::42")).isEqualTo("2001:db8:0:0:0:0:0:99");
    }

    @Test
    void rangesMatchOnThePrefixOnly() {
        TrustedProxies proxies = TrustedProxies.parse(List.of("172.16.0.0/12"));

        assertThat(proxies.contains(TrustedProxies.literal("172.31.255.255"))).isTrue();
        assertThat(proxies.contains(TrustedProxies.literal("172.32.0.0"))).isFalse();
        assertThat(proxies.contains(TrustedProxies.literal("::ffff:ac10:1"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0/33", "10.0.0.0/x", "localhost", "10.0.0.0/-1"})
    void aMisconfiguredRange_failsStartup(String cidr) {
        assertThatThrownBy(() -> TrustedProxies.parse(List.of(cidr)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
