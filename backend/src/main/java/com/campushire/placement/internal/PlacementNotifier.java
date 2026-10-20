package com.campushire.placement.internal;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.notifications.Channel;
import com.bm4a.platform.modules.notifications.NotificationMessage;
import com.bm4a.platform.modules.notifications.Notifier;
import com.campushire.common.CampusModules;

/**
 * Sends placement notifications in-app and by e-mail through the platform's {@link Notifier}, in the caller's
 * transaction. Without a notifier bean (applications that exclude the notifications module) they are only logged.
 * Links point at the web app's record pages.
 */
@Component
class PlacementNotifier implements PlacementNotifications {

    static final int MAX_TITLE = 200;
    private static final Set<Channel> IN_APP_AND_EMAIL = EnumSet.of(Channel.IN_APP, Channel.EMAIL);
    private static final Logger log = LoggerFactory.getLogger(PlacementNotifier.class);

    private final ObjectProvider<Notifier> notifier;
    private final DateTimeFormatter dateTime;

    PlacementNotifier(ObjectProvider<Notifier> notifier,
            @Value("${campushire.time-zone:Asia/Kolkata}") ZoneId zone) {
        this.notifier = notifier;
        this.dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(Locale.ENGLISH).withZone(zone);
    }

    @Override
    public void drivePublished(UUID organizationId, UUID driveId, String driveTitle,
            Collection<UUID> studentUserIds) {
        send(organizationId, studentUserIds, "campushire.drive.published", "New drive: " + name(driveTitle),
                "You are eligible for this drive. Review the details and apply before registration closes.",
                link(organizationId, CampusModules.DRIVE, driveId), Map.of("driveId", driveId.toString()));
    }

    @Override
    public void driveCancelled(UUID organizationId, UUID driveId, String driveTitle,
            Collection<UUID> studentUserIds) {
        send(organizationId, studentUserIds, "campushire.drive.cancelled", "Drive cancelled: " + name(driveTitle),
                "The placement cell cancelled this drive. Your application is closed.",
                link(organizationId, CampusModules.DRIVE, driveId), Map.of("driveId", driveId.toString()));
    }

    @Override
    public void stageChanged(UUID organizationId, UUID applicationId, String driveTitle, String fromStage,
            String toStage, UUID studentUserId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", applicationId.toString());
        data.put("from", fromStage);
        data.put("to", toStage);
        send(organizationId, List.of(studentUserId), "campushire.application.stage_changed",
                name(driveTitle) + ": " + label(toStage),
                "Your application moved from " + label(fromStage) + " to " + label(toStage) + ".",
                link(organizationId, CampusModules.APPLICATION, applicationId), data);
    }

    @Override
    public void offerExtended(UUID organizationId, UUID offerId, String companyName, UUID studentUserId) {
        send(organizationId, List.of(studentUserId), "campushire.offer.extended",
                "Offer from " + (companyName == null ? "a company" : companyName),
                "Congratulations! Review the offer and accept or decline it before the deadline.",
                link(organizationId, CampusModules.OFFER, offerId), Map.of("offerId", offerId.toString()));
    }

    @Override
    public void interviewScheduled(UUID organizationId, UUID interviewId, String driveTitle, String roundName,
            Instant scheduledAt, UUID studentUserId) {
        send(organizationId, List.of(studentUserId), "campushire.interview.scheduled",
                "Interview scheduled: " + name(driveTitle),
                (roundName == null ? "An interview round" : roundName) + " is scheduled"
                        + (scheduledAt == null ? "." : " for " + dateTime.format(scheduledAt) + "."),
                link(organizationId, CampusModules.INTERVIEW, interviewId),
                Map.of("interviewId", interviewId.toString()));
    }

    private void send(UUID organizationId, Collection<UUID> recipients, String type, String title, String body,
            String link, Map<String, Object> data) {
        if (recipients.isEmpty()) {
            return;
        }
        Notifier target = notifier.getIfAvailable();
        if (target == null) {
            log.info("{} for organization {}: {} recipient(s); notifications are not available", type,
                    organizationId, recipients.size());
            return;
        }
        String shortTitle = title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE - 1) + "…" : title;
        target.notify(organizationId, recipients, NotificationMessage.of(type, shortTitle, body, link).withData(data),
                IN_APP_AND_EMAIL);
    }

    private static String link(UUID organizationId, String moduleKey, UUID recordId) {
        return "/app/" + organizationId + "/m/" + moduleKey + "/" + recordId;
    }

    private static String name(String driveTitle) {
        return driveTitle == null || driveTitle.isBlank() ? "Placement drive" : driveTitle.strip();
    }

    private static String label(String stage) {
        if (stage == null || stage.isBlank()) {
            return "new";
        }
        String text = stage.replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
