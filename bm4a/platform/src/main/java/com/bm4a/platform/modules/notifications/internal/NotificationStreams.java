package com.bm4a.platform.modules.notifications.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The Server-Sent Events connections of this replica, per user. Each connection has its own ordered send queue
 * drained on a virtual thread, so a slow client never delays others; a client that falls too far behind is
 * disconnected (it reconnects and reloads). Events are pre-built once and shared by all recipients.
 */
@Component
class NotificationStreams implements SmartLifecycle {

    static final String NOTIFICATION_EVENT = "notification";
    static final String UNREAD_COUNT_EVENT = "unread-count";
    static final long RECONNECT_MILLIS = 5_000;
    private static final int MAX_PENDING_EVENTS = 200;
    private static final MediaType UTF8_TEXT = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);
    private static final Logger log = LoggerFactory.getLogger(NotificationStreams.class);

    /** Values are immutable lists, replaced atomically, oldest connection first. */
    private final Map<UUID, List<Connection>> connections = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();
    private volatile ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private final NotificationProperties.Stream properties;
    private volatile boolean running;

    NotificationStreams(NotificationProperties properties, MeterRegistry meters) {
        this.properties = properties.stream();
        Gauge.builder("bm4a.notifications.streams", open, AtomicInteger::get)
                .description("Open notification streams on this replica")
                .register(meters);
    }

    /**
     * Opens a stream for the user, closing their oldest one beyond the per-user limit. {@code initialEvents} is
     * evaluated after the stream is registered, so nothing committed in between is missed.
     */
    SseEmitter open(UUID userId, Supplier<List<Set<DataWithMediaType>>> initialEvents) {
        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());
        Connection connection = new Connection(userId, emitter);
        emitter.onCompletion(connection::closed);
        emitter.onTimeout(connection::close);
        emitter.onError(error -> connection.closed());
        List<Connection> evicted = new ArrayList<>();
        connections.compute(userId, (id, current) -> {
            List<Connection> next = new ArrayList<>(current == null ? List.of() : current);
            next.add(connection);
            while (next.size() > properties.maxConnectionsPerUser()) {
                evicted.add(next.removeFirst());
            }
            return List.copyOf(next);
        });
        open.incrementAndGet();
        evicted.forEach(Connection::close);
        try {
            connection.enqueue(initialEvents.get());
        } catch (RuntimeException ex) {
            connection.close();
            throw ex;
        }
        return emitter;
    }

    /** The subset of {@code userIds} with at least one open stream on this replica. */
    Set<UUID> connected(Collection<UUID> userIds) {
        Set<UUID> result = new HashSet<>();
        for (UUID userId : userIds) {
            if (connections.containsKey(userId)) {
                result.add(userId);
            }
        }
        return result;
    }

    void send(UUID userId, List<Set<DataWithMediaType>> events) {
        for (Connection connection : connections.getOrDefault(userId, List.of())) {
            connection.enqueue(events);
        }
    }

    @Scheduled(fixedRateString = "${bm4a.notifications.stream.heartbeat:25s}",
            initialDelayString = "${bm4a.notifications.stream.heartbeat:25s}")
    void heartbeat() {
        List<Set<DataWithMediaType>> keepAlive = List.of(SseEmitter.event().comment("keep-alive").build());
        connections.values().forEach(list -> list.forEach(connection -> connection.enqueue(keepAlive)));
    }

    /** {@code json} is written verbatim as UTF-8 text (it is already serialized). */
    static Set<DataWithMediaType> event(String name, String id, String json) {
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(name).data(json, UTF8_TEXT);
        if (id != null) {
            builder.id(id);
        }
        return builder.build();
    }

    static Set<DataWithMediaType> reconnectHint() {
        return SseEmitter.event().reconnectTime(RECONNECT_MILLIS).comment("connected").build();
    }

    /** Also called when a stopped context is restarted, so the sender pool is recreated if needed. */
    @Override
    public void start() {
        if (senders.isShutdown()) {
            senders = Executors.newVirtualThreadPerTaskExecutor();
        }
        running = true;
    }

    /** Ends every stream so graceful shutdown does not wait for long-lived connections. */
    @Override
    public void stop() {
        running = false;
        connections.values().forEach(list -> list.forEach(Connection::close));
        senders.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void remove(Connection connection) {
        connections.computeIfPresent(connection.userId, (id, current) -> {
            List<Connection> next = current.stream().filter(other -> other != connection).toList();
            return next.isEmpty() ? null : next;
        });
    }

    private final class Connection {

        private final UUID userId;
        private final SseEmitter emitter;
        private final ArrayDeque<Set<DataWithMediaType>> pending = new ArrayDeque<>();
        private boolean draining;
        private boolean closed;

        Connection(UUID userId, SseEmitter emitter) {
            this.userId = userId;
            this.emitter = emitter;
        }

        void enqueue(List<Set<DataWithMediaType>> events) {
            if (events.isEmpty()) {
                return;
            }
            boolean overflow;
            synchronized (this) {
                if (closed) {
                    return;
                }
                overflow = pending.size() + events.size() > MAX_PENDING_EVENTS;
                if (!overflow) {
                    pending.addAll(events);
                    if (draining) {
                        return;
                    }
                    draining = true;
                }
            }
            if (overflow) {
                log.debug("Closing a notification stream of user {} that fell behind", userId);
                close();
                return;
            }
            try {
                senders.execute(this::drain);
            } catch (RejectedExecutionException ex) {
                close();
            }
        }

        private void drain() {
            while (true) {
                Set<DataWithMediaType> next;
                synchronized (this) {
                    next = closed ? null : pending.poll();
                    if (next == null) {
                        draining = false;
                        return;
                    }
                }
                try {
                    emitter.send(next);
                } catch (IOException | IllegalStateException ex) {
                    closed();
                    return;
                }
            }
        }

        /** Completes the response; the container then reports completion through {@link #closed()}. */
        void close() {
            closed();
            try {
                emitter.complete();
            } catch (IllegalStateException ex) {
                log.trace("Stream already completed", ex);
            }
        }

        /** Forgets the connection (idempotent). */
        void closed() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                pending.clear();
            }
            open.decrementAndGet();
            remove(this);
        }
    }
}
