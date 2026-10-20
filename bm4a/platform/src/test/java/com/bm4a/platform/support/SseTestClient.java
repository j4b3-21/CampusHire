package com.bm4a.platform.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A Server-Sent Events client over a real HTTP connection, sending the bearer token like the web client's fetch-based
 * SSE implementation.
 */
public final class SseTestClient implements AutoCloseable {

    public record Event(String name, String id, String data) {
    }

    private final HttpClient client;
    private final HttpResponse<Stream<String>> response;
    private final BlockingQueue<Event> pending = new LinkedBlockingQueue<>();
    private final List<Event> received = new CopyOnWriteArrayList<>();

    private SseTestClient(HttpClient client, HttpResponse<Stream<String>> response) {
        this.client = client;
        this.response = response;
        Thread.ofVirtual().name("sse-test-client").start(this::read);
    }

    public static SseTestClient connect(int port, String path, String bearer) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", bearer)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return new SseTestClient(client, client.send(request, HttpResponse.BodyHandlers.ofLines()));
    }

    public int status() {
        return response.statusCode();
    }

    public String contentType() {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    public String header(String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    /** The next event with the given name (skipping others), or an error after the timeout. */
    public Event next(String name, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            Event event = remaining <= 0 ? null : pending.poll(remaining, TimeUnit.NANOSECONDS);
            if (event == null) {
                throw new AssertionError("No '" + name + "' event within " + timeout + "; received " + received);
            }
            if (event.name().equals(name)) {
                return event;
            }
        }
    }

    public List<Event> received() {
        return List.copyOf(received);
    }

    private void read() {
        String name = "message";
        String id = null;
        List<String> data = new ArrayList<>();
        try (Stream<String> lines = response.body()) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        Event event = new Event(name, id, String.join("\n", data));
                        received.add(event);
                        pending.add(event);
                    }
                    name = "message";
                    id = null;
                    data.clear();
                } else if (!line.startsWith(":")) {
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1);
                    value = value.startsWith(" ") ? value.substring(1) : value;
                    switch (field) {
                        case "event" -> name = value;
                        case "id" -> id = value;
                        case "data" -> data.add(value);
                        default -> {
                        }
                    }
                }
            }
        } catch (RuntimeException ex) {
            // The stream ends when the test closes the client.
        }
    }

    @Override
    public void close() {
        response.body().close();
        client.close();
    }
}
