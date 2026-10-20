/**
 * Notifications (ADR-007): in-app notifications with a real-time Server-Sent Events stream fanned out across replicas
 * through Redis pub/sub, and transactional e-mail through an outbox delivered by a scheduled SMTP worker. Other
 * modules send both through {@link Notifier}; rows are written in the caller's transaction. Implements tenancy's
 * {@code InvitationMailer}, so invitations are e-mailed.
 */
@ApplicationModule(id = "notifications", displayName = "Notifications",
        allowedDependencies = { "shared", "users", "tenancy" })
package com.bm4a.platform.modules.notifications;

import org.springframework.modulith.ApplicationModule;
