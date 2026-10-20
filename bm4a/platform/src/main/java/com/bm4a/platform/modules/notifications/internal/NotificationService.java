package com.bm4a.platform.modules.notifications.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.bm4a.platform.modules.notifications.Channel;
import com.bm4a.platform.modules.notifications.EmailMessage;
import com.bm4a.platform.modules.notifications.NotificationMessage;
import com.bm4a.platform.modules.notifications.Notifier;
import com.bm4a.platform.modules.notifications.internal.EmailOutboxRepository.NewEmail;
import com.bm4a.platform.modules.notifications.internal.MailTemplates.RenderedMail;
import com.bm4a.platform.modules.notifications.internal.NotificationFanout.Delivery;
import com.bm4a.platform.modules.notifications.internal.NotificationRepository.NewNotification;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.users.UserDirectory;
import com.bm4a.platform.modules.users.UserDirectory.UserSummary;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.ObjectNode;

@Service
public class NotificationService implements Notifier {

    static final int MAX_DATA_BYTES = 16 * 1024;
    private static final int USER_LOOKUP_CHUNK = 1000;
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final EmailOutboxRepository outbox;
    private final UserDirectory users;
    private final MailTemplates templates;
    private final NotificationFanout fanout;
    private final NotificationStreams streams;
    private final AppBranding branding;
    private final JsonColumns json;
    private final Clock clock;

    NotificationService(NotificationRepository notifications, EmailOutboxRepository outbox, UserDirectory users,
            MailTemplates templates, NotificationFanout fanout, NotificationStreams streams, AppBranding branding,
            JsonColumns json, Clock clock) {
        this.notifications = notifications;
        this.outbox = outbox;
        this.users = users;
        this.templates = templates;
        this.fanout = fanout;
        this.streams = streams;
        this.branding = branding;
        this.json = json;
        this.clock = clock;
    }

    // ---- Notifier ------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public void notify(UUID organizationId, Collection<UUID> userIds, NotificationMessage message,
            Set<Channel> channels) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(channels, "channels");
        if (userIds == null || userIds.isEmpty() || channels.isEmpty()) {
            return;
        }
        Map<UUID, UserSummary> recipients = recipients(userIds);
        if (recipients.isEmpty()) {
            return;
        }
        Instant now = SqlValues.now(clock);
        if (channels.contains(Channel.IN_APP)) {
            String data = serializeData(message.data());
            ObjectNode dataNode = json.readObject(data);
            List<NewNotification> rows = new ArrayList<>(recipients.size());
            List<Delivery> deliveries = new ArrayList<>(recipients.size());
            for (UUID userId : recipients.keySet()) {
                UUID id = UUID.randomUUID();
                rows.add(new NewNotification(id, organizationId, userId, message.type(), message.title(),
                        message.body(), message.link(), data, now));
                deliveries.add(new Delivery(userId, new NotificationView(id, organizationId, message.type(),
                        message.title(), message.body(), message.link(), dataNode, false, null, now)));
            }
            notifications.insertAll(rows);
            afterCommit(() -> fanout.publish(deliveries, List.of()));
        }
        if (channels.contains(Channel.EMAIL)) {
            RenderedMail mail = templates.render(email(message));
            List<NewEmail> rows = new ArrayList<>();
            for (UserSummary user : recipients.values()) {
                Optional<String> address = normalizeAddress(user.email());
                if (address.isEmpty()) {
                    log.debug("User {} has no usable e-mail address; skipping e-mail", user.id());
                    continue;
                }
                rows.add(new NewEmail(UUID.randomUUID(), organizationId, address.get(), mail.subject(), mail.html(),
                        mail.text(), now));
            }
            outbox.insertAll(rows);
        }
    }

    @Override
    @Transactional
    public void sendEmail(UUID organizationId, String to, EmailMessage message) {
        Objects.requireNonNull(message, "message");
        String address = normalizeAddress(to)
                .orElseThrow(() -> new IllegalArgumentException("to must be a single valid e-mail address"));
        RenderedMail mail = templates.render(message);
        outbox.insertAll(List.of(new NewEmail(UUID.randomUUID(), organizationId, address, mail.subject(), mail.html(),
                mail.text(), SqlValues.now(clock))));
    }

    // ---- the caller's notifications ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<NotificationView> list(UUID userId, boolean unreadOnly, PageQuery page) {
        return PageResponse.of(notifications.page(userId, unreadOnly, page.offset(), page.size()), page,
                notifications.count(userId, unreadOnly));
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notifications.count(userId, true);
    }

    /**
     * Opens a real-time stream for the user. The first events are a reconnect hint and the current unread count;
     * then {@code notification} and {@code unread-count} events follow as they happen.
     */
    public SseEmitter openStream(UUID userId) {
        return streams.open(userId, () -> List.of(NotificationStreams.reconnectHint(),
                NotificationFanout.unreadCountEvent(notifications.count(userId, true))));
    }

    /** Idempotent; another user's notification is a 404. */
    @Transactional
    public NotificationView markRead(UUID userId, UUID notificationId) {
        Instant now = SqlValues.now(clock);
        NotificationView view = notifications.markRead(userId, notificationId, now)
                .orElseThrow(() -> ApiException.notFound("Notification"));
        if (now.equals(view.readAt())) {
            afterCommit(() -> fanout.publish(List.of(), List.of(userId)));
        }
        return view;
    }

    @Transactional
    public int markAllRead(UUID userId) {
        int updated = notifications.markAllRead(userId, SqlValues.now(clock));
        if (updated > 0) {
            afterCommit(() -> fanout.publish(List.of(), List.of(userId)));
        }
        return updated;
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** Known users among {@code userIds}, in the given order, without duplicates. */
    private Map<UUID, UserSummary> recipients(Collection<UUID> userIds) {
        List<UUID> distinct = List.copyOf(new LinkedHashSet<>(userIds.stream().filter(Objects::nonNull).toList()));
        Map<UUID, UserSummary> found = new LinkedHashMap<>();
        for (int from = 0; from < distinct.size(); from += USER_LOOKUP_CHUNK) {
            List<UUID> chunk = distinct.subList(from, Math.min(distinct.size(), from + USER_LOOKUP_CHUNK));
            Map<UUID, UserSummary> summaries = users.findAll(chunk);
            chunk.stream().filter(summaries::containsKey).forEach(id -> found.put(id, summaries.get(id)));
        }
        return found;
    }

    private EmailMessage email(NotificationMessage message) {
        List<String> paragraphs = message.body() == null ? List.of()
                : Arrays.stream(message.body().split("\\R\\s*\\R")).map(String::strip).filter(p -> !p.isEmpty())
                        .toList();
        String link = branding.resolve(message.link());
        return EmailMessage.generic(message.title(), message.title(), paragraphs,
                link == null ? null : "Open in " + branding.name(), link);
    }

    private String serializeData(Map<String, Object> data) {
        String serialized;
        try {
            serialized = json.writeValue(data);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("data must be serializable as JSON", ex);
        }
        if (serialized.length() > MAX_DATA_BYTES) {
            throw new IllegalArgumentException("data must not exceed 16 KiB as JSON");
        }
        return serialized;
    }

    /** A bare, syntactically valid address ({@code user@example.com}), or empty. */
    static Optional<String> normalizeAddress(String address) {
        if (address == null || address.isBlank() || address.length() > 254) {
            return Optional.empty();
        }
        try {
            InternetAddress parsed = new InternetAddress(address.strip(), true);
            if (parsed.getPersonal() != null || parsed.isGroup() || !parsed.getAddress().contains("@")) {
                return Optional.empty();
            }
            return Optional.of(parsed.getAddress());
        } catch (AddressException ex) {
            return Optional.empty();
        }
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
