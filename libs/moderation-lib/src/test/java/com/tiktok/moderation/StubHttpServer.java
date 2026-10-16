package com.tiktok.moderation;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A real HTTP server on a free port for the clients' tests: the request factory, the timeouts and
 * the JSON all run as they do in a service, which a mocked RestClient would skip.
 */
public final class StubHttpServer implements AutoCloseable {

    /** What a handler answers. */
    public record Reply(int status, String body, long delayMillis) {

        public static Reply ok(String body) {
            return new Reply(200, body, 0);
        }
    }

    /** One request as received. */
    public record Received(String method, String path, String body, Map<String, List<String>> headers) {

        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().getFirst())
                    .findFirst()
                    .orElse(null);
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Function<Received, Reply> handler = request -> Reply.ok("{}");

    public StubHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    body, Map.copyOf(exchange.getRequestHeaders()));
            received.add(request);
            Reply reply = handler.apply(request);
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void answer(Function<Received, Reply> handler) {
        this.handler = handler;
    }

    public List<Received> received() {
        return received;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
