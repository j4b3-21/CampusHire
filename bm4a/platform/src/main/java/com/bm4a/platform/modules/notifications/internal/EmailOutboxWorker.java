package com.bm4a.platform.modules.notifications.internal;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.modules.notifications.internal.EmailOutboxRepository.OutboxRow;
import com.bm4a.platform.modules.shared.persistence.SqlValues;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Delivers the e-mail outbox over SMTP. Each run claims due rows in batches ({@code FOR UPDATE SKIP LOCKED}, so
 * replicas never send the same row concurrently), sends a batch over one SMTP connection outside any transaction and
 * records the outcome: sent rows lose their bodies, failed ones are retried with exponential backoff and become
 * {@code FAILED} after the last attempt. Delivery is at-least-once: a worker dying between sending and recording
 * leaves the row to be sent again after its lease expires.
 * <p>
 * Without an SMTP host ({@code SMTP_HOST}, or an application-provided {@link JavaMailSender}) rows stay
 * {@code PENDING} until one is configured.
 */
@Component
class EmailOutboxWorker {

    static final Duration LEASE = Duration.ofMinutes(10);
    private static final int MAX_BATCHES_PER_RUN = 20;
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final Logger log = LoggerFactory.getLogger(EmailOutboxWorker.class);

    private final EmailOutboxRepository outbox;
    private final TransactionTemplate transactions;
    private final NotificationProperties.Email properties;
    private final Clock clock;
    private final JavaMailSender sender;
    private final InternetAddress from;
    private final String disabledReason;
    private final AtomicBoolean disabledLogged = new AtomicBoolean();
    private final Counter sent;
    private final Counter retried;
    private final Counter failed;

    EmailOutboxWorker(EmailOutboxRepository outbox, PlatformTransactionManager transactionManager,
            NotificationProperties properties, AppBranding branding, ObjectProvider<JavaMailSender> mailSenders,
            MeterRegistry meters, Clock clock) {
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties.email();
        this.clock = clock;
        NotificationProperties.Smtp smtp = this.properties.smtp();
        this.sender = smtp.configured() ? smtpSender(smtp) : mailSenders.getIfAvailable();
        this.from = sender == null ? null : sender(this.properties.from(), smtp.username(), branding);
        if (!this.properties.enabled()) {
            disabledReason = "disabled by configuration (bm4a.notifications.email.enabled=false)";
        } else if (sender == null) {
            disabledReason = "not configured (set SMTP_HOST)";
        } else if (from == null) {
            disabledReason = "not possible: MAIL_FROM is not a valid address";
        } else {
            disabledReason = null;
        }
        this.sent = outcome(meters, "sent");
        this.retried = outcome(meters, "retried");
        this.failed = outcome(meters, "failed");
    }

    @Scheduled(fixedDelayString = "${bm4a.notifications.email.poll-interval:5s}",
            initialDelayString = "${bm4a.notifications.email.poll-interval:5s}")
    void poll() {
        if (disabledReason != null) {
            if (disabledLogged.compareAndSet(false, true)) {
                log.warn("E-mail delivery is {}; queued e-mails stay pending", disabledReason);
            }
            return;
        }
        for (int i = 0; i < MAX_BATCHES_PER_RUN && runBatch() == properties.batchSize(); i++) {
            log.debug("E-mail batch full; claiming the next one");
        }
    }

    /** @return number of rows claimed */
    private int runBatch() {
        Instant now = SqlValues.now(clock);
        List<OutboxRow> claimed = transactions.execute(status -> {
            outbox.failAbandoned(now, properties.maxAttempts());
            return outbox.claim(now, now.plus(LEASE), properties.batchSize(), properties.maxAttempts());
        });
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        Map<MimeMessage, OutboxRow> messages = new LinkedHashMap<>();
        Map<OutboxRow, Exception> failures = new LinkedHashMap<>();
        for (OutboxRow row : claimed) {
            try {
                messages.put(mimeMessage(row), row);
            } catch (MessagingException | RuntimeException ex) {
                failures.put(row, ex);
            }
        }
        if (!messages.isEmpty()) {
            try {
                sender.send(messages.keySet().toArray(MimeMessage[]::new));
            } catch (MailSendException ex) {
                Map<Object, Exception> failed = ex.getFailedMessages();
                messages.forEach((message, row) -> {
                    if (failed.isEmpty() || failed.containsKey(message)) {
                        failures.put(row, failed.getOrDefault(message, ex));
                    }
                });
            } catch (MailException ex) {
                messages.values().forEach(row -> failures.put(row, ex));
            }
        }
        record(claimed, failures);
        return claimed.size();
    }

    private void record(List<OutboxRow> claimed, Map<OutboxRow, Exception> failures) {
        Instant now = SqlValues.now(clock);
        List<UUID> delivered = new ArrayList<>();
        claimed.stream().filter(row -> !failures.containsKey(row)).forEach(row -> delivered.add(row.id()));
        transactions.executeWithoutResult(status -> {
            outbox.markSent(delivered, now);
            failures.forEach((row, ex) -> {
                String error = describe(ex);
                if (permanent(ex) || row.attempts() >= properties.maxAttempts()) {
                    outbox.markFailed(row.id(), error);
                    failed.increment();
                    log.warn("E-mail {} failed permanently after {} attempt(s): {}", row.id(), row.attempts(),
                            error);
                } else {
                    outbox.markRetry(row.id(), now.plus(backoff(row.attempts())), error);
                    retried.increment();
                    log.info("E-mail {} attempt {} failed, retrying: {}", row.id(), row.attempts(), error);
                }
            });
        });
        sent.increment(delivered.size());
    }

    /** Delay after the given failed attempt (1-based): initial, doubled per attempt, capped. */
    Duration backoff(int attempt) {
        Duration delay = properties.initialBackoff().multipliedBy(1L << Math.min(Math.max(attempt - 1, 0), 30));
        return delay.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : delay;
    }

    private MimeMessage mimeMessage(OutboxRow row) throws MessagingException {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(new InternetAddress(row.to(), true));
        helper.setSubject(row.subject());
        if (row.html() == null || row.text() == null) {
            throw new IllegalStateException("message body is missing");
        }
        helper.setText(row.text(), row.html());
        message.setHeader("Auto-Submitted", "auto-generated");
        return message;
    }

    /** Errors that retrying cannot fix: malformed messages and recipients the server rejected outright. */
    private static boolean permanent(Exception ex) {
        if (ex instanceof AddressException || ex instanceof IllegalStateException) {
            return true;
        }
        return ex instanceof SendFailedException failure && failure.getInvalidAddresses() != null
                && failure.getInvalidAddresses().length > 0;
    }

    private static String describe(Exception ex) {
        String text = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        String singleLine = text.replaceAll("\\s+", " ").strip();
        return singleLine.length() > MAX_ERROR_LENGTH ? singleLine.substring(0, MAX_ERROR_LENGTH) : singleLine;
    }

    private static JavaMailSender smtpSender(NotificationProperties.Smtp smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        String protocol = smtp.ssl() ? "smtps" : "smtp";
        boolean auth = smtp.username() != null && !smtp.username().isBlank();
        sender.setProtocol(protocol);
        sender.setHost(smtp.host().strip());
        sender.setPort(smtp.port());
        sender.setDefaultEncoding("UTF-8");
        if (auth) {
            sender.setUsername(smtp.username().strip());
            sender.setPassword(smtp.password());
        }
        Properties mail = sender.getJavaMailProperties();
        String prefix = "mail." + protocol + ".";
        String timeout = Long.toString(smtp.timeout().toMillis());
        mail.setProperty(prefix + "auth", Boolean.toString(auth));
        mail.setProperty(prefix + "connectiontimeout", timeout);
        mail.setProperty(prefix + "timeout", timeout);
        mail.setProperty(prefix + "writetimeout", timeout);
        if (smtp.ssl()) {
            mail.setProperty(prefix + "ssl.checkserveridentity", "true");
        } else if (smtp.starttls()) {
            mail.setProperty(prefix + "starttls.enable", "true");
            mail.setProperty(prefix + "starttls.required", "true");
            mail.setProperty(prefix + "ssl.checkserveridentity", "true");
        }
        return sender;
    }

    /**
     * {@code MAIL_FROM} ({@code Name <address>} or a bare address, which gets the application name), else the SMTP
     * user name if it is an address, else {@code no-reply@<web application host>}; {@code null} if invalid.
     */
    private static InternetAddress sender(String configured, String username, AppBranding branding) {
        String candidate = configured != null && !configured.isBlank() ? configured.strip()
                : username != null && username.contains("@") ? username.strip()
                        : "no-reply@" + URI.create(branding.root()).getHost();
        try {
            InternetAddress address = new InternetAddress(candidate, true);
            if (address.getPersonal() == null) {
                address.setPersonal(branding.name(), "UTF-8");
            }
            return address;
        } catch (AddressException | UnsupportedEncodingException ex) {
            log.error("Invalid e-mail sender address '{}': {}", candidate, ex.getMessage());
            return null;
        }
    }

    private static Counter outcome(MeterRegistry meters, String outcome) {
        return Counter.builder("bm4a.notifications.email")
                .description("E-mail delivery attempts by outcome")
                .tag("outcome", outcome)
                .register(meters);
    }
}
