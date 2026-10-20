package com.bm4a.platform.modules.notifications.internal;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.modules.notifications.EmailMessage;
import com.bm4a.platform.modules.notifications.Notifier;
import com.bm4a.platform.modules.tenancy.InvitationMail;
import com.bm4a.platform.modules.tenancy.InvitationMailer;

/**
 * Queues invitation e-mail through the outbox. Tenancy calls this after the invitation committed, from a transaction
 * synchronization; the outbox row therefore needs its own transaction ({@code REQUIRES_NEW}), otherwise it would join
 * the already-committed one and never be written.
 */
@Component
class NotificationInvitationMailer implements InvitationMailer {

    private static final DateTimeFormatter EXPIRY = DateTimeFormatter
            .ofPattern("d MMMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private static final int MAX_SUBJECT = 500;

    private final Notifier notifier;
    private final AppBranding branding;
    private final TransactionTemplate newTransaction;

    NotificationInvitationMailer(Notifier notifier, AppBranding branding, PlatformTransactionManager transactions) {
        this.notifier = notifier;
        this.branding = branding;
        this.newTransaction = new TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void send(InvitationMail mail) {
        String organization = singleLine(mail.organizationName());
        String inviter = mail.inviterName() == null || mail.inviterName().isBlank() ? null
                : singleLine(mail.inviterName());
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("organizationName", organization);
        model.put("inviterName", inviter);
        model.put("roleLabel", mail.roleLabel());
        model.put("acceptUrl", mail.acceptUrl());
        model.put("expiresAt", EXPIRY.format(mail.expiresAt()));
        String subject = (inviter == null ? "You are invited to join " : inviter + " invited you to join ")
                + organization + " on " + branding.name();
        EmailMessage message = EmailMessage.of(
                subject.length() > MAX_SUBJECT ? subject.substring(0, MAX_SUBJECT - 3) + "..." : subject,
                EmailMessage.INVITATION, model);
        newTransaction.executeWithoutResult(status -> notifier.sendEmail(mail.organizationId(), mail.email(),
                message));
    }

    private static String singleLine(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }
}
