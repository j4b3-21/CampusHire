package com.bm4a.platform.modules.notifications;

import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

import com.bm4a.platform.support.RedisMailContainers;
import com.bm4a.platform.support.RedisMailIntegrationTest;
import com.bm4a.platform.support.SseTestClient;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;
import com.bm4a.platform.support.TestJwts;
import com.jayway.jsonpath.JsonPath;

/**
 * E-mail delivery through a real SMTP server (Mailpit) and real-time streams over HTTP with Redis fan-out.
 */
@RedisMailIntegrationTest
class NotificationDeliveryIntegrationTest {

    static final String CHANNEL = "bm4a:notifications";
    static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    MockMvc mvc;

    @Autowired
    Notifier notifier;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    @Qualifier("mailpitContainer")
    GenericContainer<?> mailpit;

    @LocalServerPort
    int port;

    HttpClient http;

    @BeforeEach
    void setUp() {
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    @Test
    void workerDeliversQueuedMailOverSmtpAndDropsTheBodies() throws Exception {
        String to = "dest-" + UUID.randomUUID() + "@example.test";
        transactions.executeWithoutResult(status -> notifier.sendEmail(null, to, EmailMessage.generic(
                "Your offer letter", "Offer released", List.of("Congratulations!", "Please review the offer."),
                "View offer", "/app/offers/1")));

        Map<String, Object> row = awaitOutbox(to, "SENT");
        assertThat(row.get("html_body")).isNull();
        assertThat(row.get("text_body")).isNull();
        assertThat(row).containsEntry("subject", "Your offer letter").containsEntry("attempts", 1);
        assertThat(row.get("sent_at")).isNotNull();

        String message = awaitMailpitMessage(to);
        assertThat(JsonPath.<String>read(message, "$.Subject")).isEqualTo("Your offer letter");
        assertThat(JsonPath.<String>read(message, "$.From.Name")).isEqualTo("Platform Tests");
        assertThat(JsonPath.<String>read(message, "$.From.Address")).isEqualTo("noreply@bm4a.test");
        assertThat(JsonPath.<String>read(message, "$.HTML")).contains("CampusHire", "Offer released",
                "Please review the offer.", "href=\"http://localhost:3000/app/offers/1\"");
        assertThat(JsonPath.<String>read(message, "$.Text")).contains("Congratulations!",
                "View offer: http://localhost:3000/app/offers/1", "sent by CampusHire");
    }

    @Test
    void invitationEmailCarriesTheAcceptLink() throws Exception {
        TestApi api = new TestApi(mvc);
        Caller owner = api.as(TestJwts.newUser().name("Priya Rao"));
        String org = createOrg(owner, "Placement Cell");
        String email = "candidate-" + UUID.randomUUID() + "@example.test";
        String acceptUrl = read(owner.post("/api/v1/orgs/{org}/invitations",
                "{\"email\":\"" + email + "\",\"membershipRole\":\"MEMBER\"}", org)
                .andExpect(status().isCreated()).andReturn(), "$.acceptUrl");

        String message = awaitMailpitMessage(email);
        assertThat(JsonPath.<String>read(message, "$.Subject"))
                .isEqualTo("Priya Rao invited you to join Placement Cell on CampusHire");
        assertThat(JsonPath.<String>read(message, "$.Text")).contains(acceptUrl, "as Member");
        assertThat(JsonPath.<String>read(message, "$.HTML")).contains("href=\"" + acceptUrl + "\"",
                "Accept invitation");
        Map<String, Object> row = awaitOutbox(email, "SENT");
        assertThat(row.get("text_body")).isNull();
        assertThat(row.get("organization_id")).hasToString(org);
    }

    @Test
    void failedDeliveriesAreRetriedWithBackoffAndFailAfterTheLastAttempt() throws Exception {
        String retried = "retry-" + UUID.randomUUID() + "@example.test";
        String abandoned = "abandon-" + UUID.randomUUID() + "@example.test";
        RedisMailContainers.pause(mailpit);
        try {
            Instant queuedAt = Instant.now();
            for (String to : List.of(retried, abandoned)) {
                transactions.executeWithoutResult(status -> notifier.sendEmail(null, to,
                        EmailMessage.generic("Reminder", "Reminder", List.of("Test"), null, null)));
            }
            for (String to : List.of(retried, abandoned)) {
                Map<String, Object> row = awaitOutbox(to, "PENDING", 1);
                assertThat((String) row.get("last_error")).isNotBlank();
                assertThat(row.get("text_body")).isNotNull();
                Instant next = jdbc.sql("SELECT next_attempt_at FROM email_outbox WHERE to_address = :to")
                        .param("to", to).query(OffsetDateTime.class).single().toInstant();
                assertThat(next).isBetween(queuedAt.plusSeconds(55), Instant.now().plusSeconds(61));
            }

            jdbc.sql("UPDATE email_outbox SET attempts = 7, next_attempt_at = now() WHERE to_address = :to")
                    .param("to", abandoned).update();
            Map<String, Object> failed = awaitOutbox(abandoned, "FAILED", 8);
            assertThat((String) failed.get("last_error")).isNotBlank();
        } finally {
            RedisMailContainers.unpause(mailpit);
        }

        jdbc.sql("UPDATE email_outbox SET next_attempt_at = now() WHERE to_address = :to").param("to", retried)
                .update();
        Map<String, Object> sent = awaitOutbox(retried, "SENT", 2);
        assertThat(sent.get("last_error")).isNull();
        awaitMailpitMessage(retried);
        assertThat(mailpitMessageId(abandoned)).isEmpty();
    }

    @Test
    void streamDeliversLocalNotificationsAndThoseRelayedByOtherReplicas() throws Exception {
        TestApi api = new TestApi(mvc);
        TestJwts.Builder identity = TestJwts.newUser();
        UUID alice = UUID.fromString(api.as(identity).id());
        List<String> published = new CopyOnWriteArrayList<>();
        RedisConnection observer = redis.getRequiredConnectionFactory().getConnection();
        observer.subscribe((message, pattern) -> published.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                CHANNEL.getBytes(StandardCharsets.UTF_8));

        try (SseTestClient stream = SseTestClient.connect(port, "/api/v1/notifications/stream", identity.bearer())) {
            assertThat(stream.status()).isEqualTo(200);
            assertThat(stream.contentType()).startsWith("text/event-stream");
            assertThat(stream.header("X-Accel-Buffering")).isEqualTo("no");
            assertThat(stream.next("unread-count", WAIT).data()).isEqualTo("{\"count\":0}");

            transactions.executeWithoutResult(status -> notifier.notify(null, List.of(alice),
                    NotificationMessage.of("placement.update", "Shortlisted", "You were shortlisted", null),
                    Set.of(Channel.IN_APP)));
            SseTestClient.Event local = stream.next("notification", WAIT);
            assertThat(JsonPath.<String>read(local.data(), "$.title")).isEqualTo("Shortlisted");
            assertThat(local.id()).isEqualTo(JsonPath.read(local.data(), "$.id"));
            assertThat(stream.next("unread-count", WAIT).data()).isEqualTo("{\"count\":1}");
            await().atMost(WAIT).until(() -> published.stream().anyMatch(json -> json.contains(local.id())));
            String ownMessage = published.stream().filter(json -> json.contains(local.id())).findFirst().orElseThrow();
            assertThat(JsonPath.<String>read(ownMessage, "$.deliveries[0].userId")).isEqualTo(alice.toString());

            UUID remoteId = UUID.randomUUID();
            String relayed = """
                    {"origin": "replica-b", "countUsers": [],
                     "deliveries": [{"userId": "%s", "notification": {"id": "%s", "organizationId": null,
                       "type": "placement.update", "title": "From replica B", "body": null, "link": null, "data": {},
                       "read": false, "readAt": null, "createdAt": "2026-10-20T10:00:00Z"}}]}
                    """.formatted(alice, remoteId);
            await().atMost(WAIT).until(() -> redis.convertAndSend(CHANNEL, relayed) > 1);
            SseTestClient.Event remote = stream.next("notification", WAIT);
            assertThat(remote.id()).isEqualTo(remoteId.toString());
            assertThat(JsonPath.<String>read(remote.data(), "$.title")).isEqualTo("From replica B");

            assertThat(stream.received().stream().filter(event -> local.id().equals(event.id()))).hasSize(1);
        } finally {
            observer.close();
        }
    }

    @Test
    void streamRejectsMissingTokens() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/notifications/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private Map<String, Object> awaitOutbox(String to, String status) {
        return awaitOutbox(to, status, null);
    }

    private Map<String, Object> awaitOutbox(String to, String status, Integer attempts) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            Map<String, Object> row = jdbc.sql("SELECT * FROM email_outbox WHERE to_address = :to").param("to", to)
                    .query().singleRow();
            return status.equals(row.get("status")) && (attempts == null || attempts.equals(row.get("attempts")));
        });
        return jdbc.sql("SELECT * FROM email_outbox WHERE to_address = :to").param("to", to).query().singleRow();
    }

    /** The full Mailpit message sent to {@code to}, once it arrived. */
    private String awaitMailpitMessage(String to) throws Exception {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).until(() -> mailpitMessageId(to).isPresent());
        return get("/api/v1/message/" + mailpitMessageId(to).orElseThrow());
    }

    private Optional<String> mailpitMessageId(String to) throws Exception {
        List<String> ids = JsonPath.read(get("/api/v1/messages?limit=500"),
                "$.messages[?(@.To[0].Address == '" + to + "')].ID");
        return ids.stream().findFirst();
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                URI.create(RedisMailContainers.mailpitApi(mailpit) + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
