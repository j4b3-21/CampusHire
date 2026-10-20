package com.bm4a.platform.modules.notifications;

import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;
import com.bm4a.platform.support.TestJwts;

/**
 * Notifier rows, the notification API and the stream with local delivery (the shared context has no Redis and no
 * SMTP server, so e-mail stays queued; delivery is covered by {@link NotificationDeliveryIntegrationTest}).
 */
@IntegrationTest
class NotificationsIntegrationTest {

    static final String NOTIFICATIONS = "/api/v1/notifications";

    @Autowired
    MockMvc mvc;

    @Autowired
    Notifier notifier;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactions;

    TestApi api;
    TestJwts.Builder aliceIdentity;
    Caller alice;
    Caller bob;
    UUID org;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        aliceIdentity = TestJwts.newUser();
        alice = api.as(aliceIdentity);
        bob = api.newUser();
        org = UUID.fromString(createOrg(alice, "Notified"));
        bob.id();
    }

    @Test
    void usersOnlyEverSeeTheirOwnNotifications() throws Exception {
        UUID aliceId = UUID.fromString(alice.id());
        UUID bobId = UUID.fromString(bob.id());
        notify(List.of(aliceId, bobId, UUID.randomUUID(), aliceId), NotificationMessage
                .of("drive.published", "Drive published", "Acme opened a drive", "/app/" + org + "/m/drive/1")
                .withData(Map.of("driveId", "1")), Set.of(Channel.IN_APP));
        notify(List.of(aliceId), NotificationMessage.of("result.announced", "Results are out", null, null),
                Set.of(Channel.IN_APP));

        MvcResult page = alice.get(NOTIFICATIONS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].title").value("Results are out"))
                .andExpect(jsonPath("$.items[0].read").value(false))
                .andExpect(jsonPath("$.items[0].readAt").value(nullValue()))
                .andExpect(jsonPath("$.items[1].type").value("drive.published"))
                .andExpect(jsonPath("$.items[1].organizationId").value(org.toString()))
                .andExpect(jsonPath("$.items[1].body").value("Acme opened a drive"))
                .andExpect(jsonPath("$.items[1].link").value("/app/" + org + "/m/drive/1"))
                .andExpect(jsonPath("$.items[1].data.driveId").value("1"))
                .andExpect(jsonPath("$.items[1].userId").doesNotExist())
                .andReturn();
        String aliceNotification = read(page, "$.items[1].id");

        MvcResult bobs = bob.get(NOTIFICATIONS)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Drive published"))
                .andReturn();
        String bobNotification = read(bobs, "$.items[0].id");

        bob.post(NOTIFICATIONS + "/{id}/read", "", aliceNotification)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        alice.post(NOTIFICATIONS + "/{id}/read", "", bobNotification).andExpect(status().isNotFound());
        alice.post(NOTIFICATIONS + "/{id}/read", "", UUID.randomUUID()).andExpect(status().isNotFound());
        bob.post(NOTIFICATIONS + "/read-all", "").andExpect(jsonPath("$.updated").value(1));
        alice.get(NOTIFICATIONS + "/unread-count").andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void unreadCountReadAndReadAll() throws Exception {
        UUID aliceId = UUID.fromString(alice.id());
        for (int i = 1; i <= 3; i++) {
            notify(List.of(aliceId), NotificationMessage.of("test.item", "Item " + i, null, null),
                    Set.of(Channel.IN_APP));
        }
        alice.get(NOTIFICATIONS + "/unread-count").andExpect(jsonPath("$.count").value(3));
        String newest = read(alice.get(NOTIFICATIONS + "?size=1").andExpect(jsonPath("$.totalPages").value(3))
                .andReturn(), "$.items[0].id");

        MvcResult first = alice.post(NOTIFICATIONS + "/{id}/read", "", newest)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andReturn();
        String readAt = read(first, "$.readAt");
        alice.post(NOTIFICATIONS + "/{id}/read", "", newest)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").value(readAt));
        alice.get(NOTIFICATIONS + "/unread-count").andExpect(jsonPath("$.count").value(2));
        alice.get(NOTIFICATIONS + "?unread=true")
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].title").value("Item 2"));

        alice.post(NOTIFICATIONS + "/read-all", "").andExpect(jsonPath("$.updated").value(2));
        alice.post(NOTIFICATIONS + "/read-all", "").andExpect(jsonPath("$.updated").value(0));
        alice.get(NOTIFICATIONS + "/unread-count").andExpect(jsonPath("$.count").value(0));
        alice.get(NOTIFICATIONS + "?unread=true").andExpect(jsonPath("$.totalElements").value(0));
        alice.get(NOTIFICATIONS).andExpect(jsonPath("$.totalElements").value(3));
        alice.get(NOTIFICATIONS + "?size=101").andExpect(status().isBadRequest());
    }

    @Test
    void emailChannelQueuesRenderedMailForRecipientsWithAnAddress() throws Exception {
        TestJwts.Builder carolIdentity = TestJwts.newUser();
        Caller carol = api.as(carolIdentity);
        UUID carolId = UUID.fromString(carol.id());
        notify(List.of(carolId), NotificationMessage.of("interview.scheduled", "Interview <scheduled>",
                "Your interview is on Monday.\n\nBring your <ID card>.", "/app/" + org + "/m/interview/7"),
                Set.of(Channel.EMAIL));

        Map<String, Object> row = jdbc.sql("SELECT * FROM email_outbox WHERE to_address = :to")
                .param("to", carolIdentity.email()).query().singleRow();
        assertThat(row).containsEntry("status", "PENDING").containsEntry("attempts", 0)
                .containsEntry("subject", "Interview <scheduled>").containsEntry("organization_id", org);
        String html = (String) row.get("html_body");
        String text = (String) row.get("text_body");
        assertThat(html).contains("Interview &lt;scheduled&gt;", "Bring your &lt;ID card&gt;.",
                "href=\"http://localhost:3000/app/" + org + "/m/interview/7\"", "Open in BM4A")
                .doesNotContain("<ID card>");
        assertThat(text).contains("Interview <scheduled>", "Your interview is on Monday.", "Bring your <ID card>.",
                "Open in BM4A: http://localhost:3000/app/" + org + "/m/interview/7");
        carol.get(NOTIFICATIONS).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void sendEmailValidatesAddressTemplateAndSubject() {
        EmailMessage message = EmailMessage.generic("Hello", "Hello", List.of("Text"), null, null);
        assertThatThrownBy(() -> notifier.sendEmail(org, "not an address", message))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notifier.sendEmail(org, "Name <name@example.test>", message))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notifier.sendEmail(org, "x@example.test",
                EmailMessage.of("Hello", "does-not-exist", Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailMessage.of("Two\nlines", EmailMessage.GENERIC, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailMessage.of("Hi", "../layout", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notifier.sendEmail(org, "x@example.test", EmailMessage.generic("Hi", "Hi",
                List.of(), "Open", "javascript:alert(1)"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationMessage.of("Bad Type", "t", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationMessage.of("ok", "t", null, "javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM email_outbox WHERE organization_id = :org").param("org", org)
                .query(Long.class).single()).isZero();
    }

    @Test
    void nothingIsWrittenWhenTheCallersTransactionRollsBack() throws Exception {
        UUID aliceId = UUID.fromString(alice.id());
        transactions.executeWithoutResult(status -> {
            notifier.notify(org, List.of(aliceId), NotificationMessage.of("test.rollback", "Rolled back", null, null),
                    Set.of(Channel.IN_APP, Channel.EMAIL));
            status.setRollbackOnly();
        });
        alice.get(NOTIFICATIONS).andExpect(jsonPath("$.totalElements").value(0));
        assertThat(jdbc.sql("SELECT count(*) FROM email_outbox WHERE organization_id = :org").param("org", org)
                .query(Long.class).single()).isZero();
    }

    @Test
    void bulkNotificationWritesOneRowPerRecipientAndChannel() {
        int recipients = 1_500;
        List<UUID> userIds = jdbc.sql("""
                INSERT INTO platform_user (id, issuer, subject, email, display_name, status, created_at, updated_at)
                SELECT gen_random_uuid(), :issuer, 'user-' || n, 'bulk-' || n || '@example.test', 'Bulk user ' || n,
                       'ACTIVE', now(), now()
                  FROM generate_series(1, :count) AS n
                RETURNING id
                """).param("issuer", "bulk-" + UUID.randomUUID()).param("count", recipients)
                .query(UUID.class).list();
        long started = System.nanoTime();
        notify(userIds, NotificationMessage.of("drive.published", "New drive", "Apply now", "/app/drives"),
                Set.of(Channel.IN_APP, Channel.EMAIL));
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE user_id IN (:ids)").param("ids", userIds)
                .query(Long.class).single()).isEqualTo(recipients);
        assertThat(jdbc.sql("SELECT count(*) FROM email_outbox WHERE organization_id = :org").param("org", org)
                .query(Long.class).single()).isEqualTo(recipients);
        assertThat(took).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void streamPushesNotificationsAndUnreadCountsCreatedByOtherRequests() throws Exception {
        UUID aliceId = UUID.fromString(alice.id());
        MvcResult stream = mvc.perform(get(NOTIFICATIONS + "/stream")
                        .header(HttpHeaders.AUTHORIZATION, aliceIdentity.bearer()))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(stream.getResponse().getContentAsString()).contains("data:{\"count\":0}"));
        assertThat(stream.getResponse().getContentType()).startsWith("text/event-stream");
        assertThat(stream.getResponse().getContentAsString()).contains("retry:5000");

        notify(List.of(aliceId), NotificationMessage.of("offer.released", "Offer released", "Congratulations",
                null), Set.of(Channel.IN_APP));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> stream.getResponse().getContentAsString().contains("data:{\"count\":1}"));
        String content = stream.getResponse().getContentAsString();
        assertThat(content).contains("event:notification", "\"title\":\"Offer released\"", "event:unread-count");
        assertThat(content.indexOf("event:notification")).isLessThan(content.indexOf("data:{\"count\":1}"));

        String id = read(alice.get(NOTIFICATIONS).andReturn(), "$.items[0].id");
        assertThat(content).contains("id:" + id);
        alice.post(NOTIFICATIONS + "/{id}/read", "", id).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).until(() -> stream.getResponse().getContentAsString()
                .substring(content.length()).contains("data:{\"count\":0}"));

        notify(List.of(UUID.fromString(bob.id())), NotificationMessage.of("other.user", "Not for Alice", null, null),
                Set.of(Channel.IN_APP));
        Thread.sleep(500);
        assertThat(stream.getResponse().getContentAsString()).doesNotContain("Not for Alice");
    }

    @Test
    void streamRequiresAuthentication() throws Exception {
        mvc.perform(get(NOTIFICATIONS + "/stream")).andExpect(status().isUnauthorized());
        mvc.perform(get(NOTIFICATIONS)).andExpect(status().isUnauthorized());
    }

    private void notify(List<UUID> userIds, NotificationMessage message, Set<Channel> channels) {
        transactions.executeWithoutResult(status -> notifier.notify(org, userIds, message, channels));
    }
}
