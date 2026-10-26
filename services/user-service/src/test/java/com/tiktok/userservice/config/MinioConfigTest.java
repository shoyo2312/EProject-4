package com.tiktok.userservice.config;

import com.sun.net.httpserver.HttpServer;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The client must never ask MinIO where the bucket is: that lookup needs s3:GetBucketLocation,
 * which this service's production account does not have. The server here refuses everything, as
 * that account would refuse the lookup, and counts what it was sent.
 */
class MinioConfigTest {

    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;

    @BeforeEach
    void startRefusingServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void presignsAnExportLinkWithoutAskingTheServerAnything() {
        MinioClient client = new MinioConfig().minioClient(new MinioProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "key", "secret", "video-media", "us-east-1"));

        assertThatCode(() -> client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket("video-media")
                .object("exports/1.zip")
                .expiry(300)
                .build()))
                .doesNotThrowAnyException();
        assertThat(requests).hasValue(0);
    }
}
