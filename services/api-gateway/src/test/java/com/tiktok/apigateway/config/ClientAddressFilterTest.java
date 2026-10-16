package com.tiktok.apigateway.config;

import com.tiktok.common.http.InternalApiToken;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * What downstream services are told about who called. No Spring context, like
 * {@link RateLimiterConfigTest}: the filter is a function of the peer, the headers and the
 * trusted ranges.
 *
 * <p>The case that matters is the forged one. Everything else here resolves an address; that test
 * is the reason the header can be believed at all.
 */
class ClientAddressFilterTest {

    private static final RateLimitProperties PROPERTIES = new RateLimitProperties(
            List.of("127.0.0.0/8", "::1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"));

    private static final InternalApiToken TOKEN = InternalApiToken.from(new MockEnvironment()
            .withProperty("INTERNAL_API_TOKEN", "internal-token-for-tests-0123456789abcdef"));

    /** The headers the next hop would see, after the filter has had the request. */
    private HttpHeaders forwardedHeaders(MockServerHttpRequest.BaseBuilder<?> request, String routeId) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR,
                Route.async().id(routeId).uri("http://localhost").predicate(e -> true).build());

        new ClientAddressFilter(PROPERTIES, TOKEN)
                .filter(exchange, next -> {
                    forwarded.set(next);
                    return Mono.empty();
                })
                .block();

        return forwarded.get().getRequest().getHeaders();
    }

    /** The value the next hop would see, after the filter has had the request. */
    private String forwarded(MockServerHttpRequest.BaseBuilder<?> request) {
        return forwardedHeaders(request, ClientAddressFilter.AUTH_ROUTE).getFirst(ClientAddressFilter.CLIENT_IP);
    }

    private MockServerHttpRequest.BaseBuilder<?> from(String peer) {
        return MockServerHttpRequest.get("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress(peer, 40000));
    }

    @Test
    void theAddressTheProxySawIsPassedOn() {
        assertThat(forwarded(from("10.0.0.5").header("X-Forwarded-For", "203.0.113.7")))
                .isEqualTo("203.0.113.7");
    }

    /**
     * The one that earns the filter. A caller who sends this header is naming the address their
     * own sign-in — or their own failed attempts — get filed under, and auth-service reads this
     * header precisely because it cannot tell a forged X-Forwarded-For entry from a real one. So
     * the value is set, never added: whatever arrived under this name is dropped.
     */
    @Test
    void anInboundClientIpHeaderIsDiscarded() {
        String result = forwarded(from("10.0.0.5")
                .header("X-Forwarded-For", "203.0.113.7")
                .header(ClientAddressFilter.CLIENT_IP, "198.51.100.66"));

        assertThat(result).isEqualTo("203.0.113.7");
    }

    @Test
    void aForgedHeaderAloneStillLosesToThePeer() {
        // Nothing but the forgery: the answer is the connection, which is the one thing a client
        // cannot write.
        assertThat(forwarded(from("198.51.100.20").header(ClientAddressFilter.CLIENT_IP, "10.0.0.1")))
                .isEqualTo("198.51.100.20");
    }

    @Test
    void aDirectCallerIsItsOwnAddress() {
        assertThat(forwarded(from("198.51.100.20"))).isEqualTo("198.51.100.20");
    }

    @Test
    void theHeaderIsAlwaysSet() {
        // Downstream reads one header and falls back to its own socket. Leaving it absent on some
        // paths would make that fallback the normal case and hide a resolution that went wrong.
        assertThat(forwarded(from("10.0.0.5"))).isNotBlank();
    }

    /**
     * auth-service believes X-Client-Ip only beside the token, so it goes there. Anywhere else it
     * would make a user's request an internal caller's, and a client's own copy is never passed on.
     */
    @Test
    void onlyRequestsToAuthServiceCarryTheInternalToken() {
        MockServerHttpRequest.BaseBuilder<?> forged = from("198.51.100.20")
                .header(InternalApiToken.HEADER, "forged");

        assertThat(forwardedHeaders(forged, ClientAddressFilter.AUTH_ROUTE).getFirst(InternalApiToken.HEADER))
                .isEqualTo(TOKEN.value());
        assertThat(forwardedHeaders(forged, "user-service").get(InternalApiToken.HEADER)).isNull();
    }
}
