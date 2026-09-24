package com.automation.service.integration.providers;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local stand-in for a provider's HTTP API ({@link HttpServer} on {@code 127.0.0.1:0}): serves
 * scripted responses per path and records every request, so tests can check method, path, query,
 * headers and body against the official API reference.
 */
public final class StubApiServer implements AutoCloseable {

    /** One recorded request. Header names are lower-cased. */
    public record Request(String method, String path, String query, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    /** A scripted response. */
    public record Response(int status, String body, Map<String, String> headers) {
        public static Response json(int status, String body) {
            return new Response(status, body, Map.of("Content-Type", "application/json"));
        }
    }

    private final HttpServer server;
    private final Map<String, Deque<Response>> responses = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    public StubApiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, String> headers = new ConcurrentHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getRawPath();
            requests.add(new Request(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(), headers, body));
            Response response = next(path);
            response.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            if (response.status() == 204 || response.body() == null) {
                ex.sendResponseHeaders(response.status(), -1);
            } else {
                byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(response.status(), bytes.length);
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public StubApiServer on(String path, Response response) {
        return onSequence(path, response);
    }

    /** Answers the requests to {@code path} in order; the last response repeats. */
    public StubApiServer onSequence(String path, Response... sequence) {
        responses.put(path, new ArrayDeque<>(List.of(sequence)));
        return this;
    }

    private synchronized Response next(String path) {
        Deque<Response> queue = responses.get(path);
        if (queue == null || queue.isEmpty()) {
            return Response.json(404, "{\"error\":\"no stub for " + path + "\"}");
        }
        return queue.size() > 1 ? queue.pollFirst() : queue.peekFirst();
    }

    public StubApiServer onJson(String path, int status, String body) {
        return on(path, Response.json(status, body));
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public Request lastRequest() {
        if (requests.isEmpty()) throw new AssertionError("no request reached the stub");
        return requests.get(requests.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
