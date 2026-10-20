package com.bm4a.platform.modules.notifications.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;

import com.bm4a.platform.modules.shared.resilience.CircuitBreaker;

import tools.jackson.databind.json.JsonMapper;

/**
 * Pushes committed notifications and unread counts to open streams. Work is queued and processed on one background
 * thread, so request threads never wait for Redis or slow clients. Local streams are always served directly; with
 * Redis enabled, every batch is also published on {@value #CHANNEL} and each replica serves the batches of the
 * others (messages carry the publishing replica's ID, so nothing is delivered twice). When Redis is unavailable,
 * delivery is local only and a warning is logged once per outage.
 */
@Component
class NotificationFanout implements SmartLifecycle, MessageListener {

    static final String CHANNEL = "bm4a:notifications";
    private static final int QUEUE_CAPACITY = 10_000;
    private static final int DELIVERIES_PER_MESSAGE = 200;
    private static final int COUNT_QUERY_CHUNK = 1000;
    private static final Duration MAX_SUBSCRIBE_BACKOFF = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(NotificationFanout.class);

    /** One notification for one recipient. */
    record Delivery(UUID userId, NotificationView notification) {
    }

    /** Wire format on {@value #CHANNEL}; {@code countUsers} get a fresh unread count. */
    record RelayMessage(String origin, List<Delivery> deliveries, List<UUID> countUsers) {
    }

    private record Job(List<Delivery> deliveries, Set<UUID> countUsers, boolean relay) {
    }

    private final String origin = UUID.randomUUID().toString();
    private final BlockingQueue<Job> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong lastDropWarning = new AtomicLong();
    private final NotificationStreams streams;
    private final NotificationRepository repository;
    private final JsonMapper json;
    private final StringRedisTemplate redis;
    private final RedisConnectionFactory connectionFactory;
    private final boolean redisEnabled;
    private final CircuitBreaker breaker;
    private final Clock clock;
    private volatile boolean running;
    private volatile Thread dispatcher;
    private volatile Thread subscriber;
    private volatile RedisConnection subscription;

    NotificationFanout(NotificationStreams streams, NotificationRepository repository, JsonMapper json,
            StringRedisTemplate redis, RedisConnectionFactory connectionFactory, NotificationProperties properties,
            Clock clock) {
        this.streams = streams;
        this.repository = repository;
        this.json = json;
        this.redis = redis;
        this.connectionFactory = connectionFactory;
        this.redisEnabled = properties.stream().redisEnabled();
        this.breaker = new CircuitBreaker(Duration.ofSeconds(5), clock);
        this.clock = clock;
    }

    /** Called after commit: new notifications, plus users whose unread count changed otherwise. */
    void publish(List<Delivery> deliveries, Collection<UUID> countUsers) {
        enqueue(new Job(List.copyOf(deliveries), new LinkedHashSet<>(countUsers), true));
    }

    private void enqueue(Job job) {
        if (!queue.offer(job)) {
            long now = clock.millis();
            long last = lastDropWarning.get();
            if (now - last > 60_000 && lastDropWarning.compareAndSet(last, now)) {
                log.warn("Notification push queue is full; real-time delivery of some notifications was skipped "
                        + "(they remain in the notification list)");
            }
        }
    }

    private void dispatch() {
        while (running) {
            try {
                Job job = queue.poll(1, TimeUnit.SECONDS);
                if (job != null) {
                    process(job);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                log.warn("Pushing notifications failed: {}", ex.toString());
            }
        }
    }

    private void process(Job job) {
        deliverLocally(job);
        if (job.relay() && redisEnabled) {
            relay(job);
        }
    }

    private void deliverLocally(Job job) {
        Set<UUID> recipients = new LinkedHashSet<>(job.countUsers());
        job.deliveries().forEach(delivery -> recipients.add(delivery.userId()));
        Set<UUID> connected = streams.connected(recipients);
        if (connected.isEmpty()) {
            return;
        }
        Map<UUID, Long> counts = unreadCounts(connected);
        Map<UUID, List<Set<DataWithMediaType>>> events = new LinkedHashMap<>();
        for (Delivery delivery : job.deliveries()) {
            if (connected.contains(delivery.userId())) {
                NotificationView view = delivery.notification();
                events.computeIfAbsent(delivery.userId(), id -> new ArrayList<>()).add(NotificationStreams.event(
                        NotificationStreams.NOTIFICATION_EVENT, view.id().toString(), json.writeValueAsString(view)));
            }
        }
        for (UUID userId : connected) {
            long unread = counts.getOrDefault(userId, 0L);
            events.computeIfAbsent(userId, id -> new ArrayList<>()).add(unreadCountEvent(unread));
        }
        events.forEach(streams::send);
    }

    static Set<DataWithMediaType> unreadCountEvent(long count) {
        return NotificationStreams.event(NotificationStreams.UNREAD_COUNT_EVENT, null, "{\"count\":" + count + "}");
    }

    private Map<UUID, Long> unreadCounts(Set<UUID> userIds) {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        List<UUID> all = List.copyOf(userIds);
        for (int from = 0; from < all.size(); from += COUNT_QUERY_CHUNK) {
            counts.putAll(repository.unreadCounts(all.subList(from, Math.min(all.size(), from + COUNT_QUERY_CHUNK))));
        }
        return counts;
    }

    private void relay(Job job) {
        if (!breaker.allowRequest()) {
            return;
        }
        try {
            List<UUID> countUsers = List.copyOf(job.countUsers());
            List<Delivery> deliveries = job.deliveries();
            int from = 0;
            do {
                int to = Math.min(deliveries.size(), from + DELIVERIES_PER_MESSAGE);
                List<Delivery> chunk = deliveries.subList(from, to);
                redis.convertAndSend(CHANNEL, json.writeValueAsString(
                        new RelayMessage(origin, chunk, from == 0 ? countUsers : List.of())));
                from += DELIVERIES_PER_MESSAGE;
            } while (from < deliveries.size());
            if (breaker.recordSuccess()) {
                log.info("Redis is reachable again; notifications are pushed to all replicas");
            }
        } catch (RuntimeException ex) {
            if (breaker.recordFailure()) {
                log.warn("Redis is unavailable; notifications are pushed to streams on this replica only: {}",
                        ex.toString());
            }
        }
    }

    /** Messages from other replicas, received on a Redis client thread: handed to the dispatcher. */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            RelayMessage relayed = json.readValue(new String(message.getBody(), StandardCharsets.UTF_8),
                    RelayMessage.class);
            if (origin.equals(relayed.origin())) {
                return;
            }
            enqueue(new Job(relayed.deliveries() == null ? List.of() : relayed.deliveries(),
                    relayed.countUsers() == null ? Set.of() : new LinkedHashSet<>(relayed.countUsers()), false));
        } catch (RuntimeException ex) {
            log.warn("Ignoring an unreadable message on {}: {}", CHANNEL, ex.toString());
        }
    }

    /**
     * Subscribes in the background, retrying until Redis is reachable, so a Redis outage never blocks startup.
     * Once subscribed, the Redis client re-subscribes by itself after reconnecting.
     */
    private void subscribe() {
        long backoff = 1_000;
        boolean warned = false;
        while (running && subscription == null) {
            RedisConnection connection = null;
            try {
                connection = connectionFactory.getConnection();
                connection.subscribe(this, CHANNEL.getBytes(StandardCharsets.UTF_8));
                subscription = connection;
                log.info("Subscribed to {} for cross-replica notification delivery", CHANNEL);
                return;
            } catch (RuntimeException ex) {
                closeQuietly(connection);
                if (!warned) {
                    log.warn("Cannot subscribe to {} yet ({}); notifications from other replicas are not pushed "
                            + "until Redis is reachable", CHANNEL, ex.toString());
                    warned = true;
                }
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = Math.min(backoff * 2, MAX_SUBSCRIBE_BACKOFF.toMillis());
        }
    }

    @Override
    public void start() {
        running = true;
        dispatcher = Thread.ofVirtual().name("bm4a-notification-fanout").start(this::dispatch);
        if (redisEnabled) {
            subscriber = Thread.ofVirtual().name("bm4a-notification-subscriber").start(this::subscribe);
        }
    }

    @Override
    public void stop() {
        running = false;
        interrupt(subscriber);
        interrupt(dispatcher);
        RedisConnection connection = subscription;
        subscription = null;
        if (connection != null) {
            try {
                if (connection.getSubscription() != null) {
                    connection.getSubscription().close();
                }
            } catch (RuntimeException ex) {
                log.debug("Closing the notification subscription failed", ex);
            }
            closeQuietly(connection);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static void interrupt(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static void closeQuietly(RedisConnection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException ex) {
                log.debug("Closing a Redis connection failed", ex);
            }
        }
    }
}
