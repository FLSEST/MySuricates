package com.automation.service.integration.providers.caldav;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local {@link HttpServer} on 127.0.0.1:0 that answers by "METHOD path" and records every request
 * (method, path, selected headers, body) — the simulated CalDAV server of the provider tests.
 */
final class FakeDavServer implements AutoCloseable {

    record Reply(int status, String body, Map<String, String> headers) {
        static Reply of(int status, String body) {
            return new Reply(status, body, Map.of());
        }
    }

    record Recorded(String method, String path, Map<String, String> headers, String body) {}

    private final HttpServer server;
    private final Map<String, List<Reply>> replies = new HashMap<>();
    final List<Recorded> requests = new CopyOnWriteArrayList<>();

    FakeDavServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, String> headers = new HashMap<>();
            for (String h : List.of("Authorization", "Depth", "If-Match", "If-None-Match", "Content-Type", "Accept")) {
                String v = ex.getRequestHeaders().getFirst(h);
                if (v != null) headers.put(h, v);
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String key = ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath();
            requests.add(new Recorded(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), headers, body));
            Reply reply = next(key);
            reply.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Queues a reply ({@code "PUT *"} matches any path); the last reply for a key is repeated once the queue is down to one. */
    FakeDavServer on(String methodAndPath, Reply reply) {
        replies.computeIfAbsent(methodAndPath, k -> new ArrayList<>()).add(reply);
        return this;
    }

    /** Replaces all queued replies for the key. */
    FakeDavServer replace(String methodAndPath, int status, String body) {
        replies.remove(methodAndPath);
        return on(methodAndPath, status, body);
    }

    FakeDavServer on(String methodAndPath, int status, String body) {
        return on(methodAndPath, Reply.of(status, body));
    }

    private synchronized Reply next(String key) {
        List<Reply> queue = replies.get(key);
        if (queue == null) queue = replies.get(key.substring(0, key.indexOf(' ')) + " *");
        if (queue == null || queue.isEmpty()) return Reply.of(404, "no fake reply for " + key);
        return queue.size() > 1 ? queue.remove(0) : queue.get(0);
    }

    List<Recorded> requests(String method) {
        return requests.stream().filter(r -> r.method().equals(method)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
