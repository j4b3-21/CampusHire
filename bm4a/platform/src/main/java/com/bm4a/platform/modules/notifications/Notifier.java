package com.bm4a.platform.modules.notifications;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Sends notifications and e-mail. Both methods only write rows (in-app notifications, e-mail outbox) and join the
 * caller's transaction, so nothing is delivered if it rolls back; real-time pushes happen after commit and e-mail is
 * sent by a background worker with retries. When calling from a {@code TransactionSynchronization#afterCommit}
 * callback, run inside a new transaction ({@code REQUIRES_NEW}), otherwise the rows are never committed.
 * Invalid messages or addresses throw {@link IllegalArgumentException}.
 */
public interface Notifier {

    /**
     * Notifies users (duplicates and unknown IDs are ignored). E-mail goes to each user's account address, skipping
     * users without one. Suited to thousands of recipients: rows are written with batched inserts.
     *
     * @param organizationId the tenant the notification belongs to, or {@code null} for a platform-level one
     */
    void notify(UUID organizationId, Collection<UUID> userIds, NotificationMessage message, Set<Channel> channels);

    /**
     * Queues one e-mail rendered from {@code message}'s template.
     *
     * @param organizationId the tenant the e-mail belongs to, or {@code null}
     */
    void sendEmail(UUID organizationId, String to, EmailMessage message);
}
