package com.bm4a.platform.modules.notifications.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.bm4a.platform.modules.notifications.internal.NotificationService;
import com.bm4a.platform.modules.notifications.internal.NotificationView;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.users.CurrentUser;

/**
 * The caller's own notifications across all organizations. Other users' notifications do not exist for the caller
 * (404).
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** Newest first; {@code unread=true} lists only unread ones. */
    @GetMapping
    PageResponse<NotificationView> list(CurrentUser user, @RequestParam(required = false) Boolean unread,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return notifications.list(user.id(), Boolean.TRUE.equals(unread), PageQuery.of(page, size));
    }

    @GetMapping("/unread-count")
    UnreadCount unreadCount(CurrentUser user) {
        return new UnreadCount(notifications.unreadCount(user.id()));
    }

    @PostMapping("/{id}/read")
    NotificationView markRead(CurrentUser user, @PathVariable UUID id) {
        return notifications.markRead(user.id(), id);
    }

    @PostMapping("/read-all")
    ReadAllResult markAllRead(CurrentUser user) {
        return new ReadAllResult(notifications.markAllRead(user.id()));
    }

    /**
     * Server-Sent Events: {@code notification} (a notification as listed) and {@code unread-count}
     * ({@code {"count": n}}), plus keep-alive comments. Proxies must not buffer the response.
     */
    @GetMapping("/stream")
    ResponseEntity<SseEmitter> stream(CurrentUser user) {
        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .body(notifications.openStream(user.id()));
    }

    record UnreadCount(long count) {
    }

    record ReadAllResult(int updated) {
    }
}
