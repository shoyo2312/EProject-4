package com.tiktok.common.http;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The request factory for every service-to-service RestClient.
 *
 * <p>These clients used {@code SimpleClientHttpRequestFactory}, which is HttpURLConnection, and
 * that keeps at most five idle connections per host ({@code http.maxConnections}). Past five
 * concurrent calls to one downstream, every call that finishes closes its socket and the next one
 * opens a new one: a TCP handshake per request, and TIME_WAIT sockets piling up under exactly the
 * load that needs them least. The JDK HttpClient keeps a keep-alive pool with no such cap.
 *
 * <p>HTTP/1.1 is pinned because the client's default, HTTP/2, opens plain {@code http://}
 * connections with an h2c upgrade attempt that none of our servers (Tomcat, uvicorn) accepts.
 */
public final class PooledRequestFactory {

    private PooledRequestFactory() {
    }

    public static ClientHttpRequestFactory create(Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
