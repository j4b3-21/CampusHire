"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { useOrg } from "@/components/app/org-context";
import { prependNotification, queryKeys, setUnreadCount } from "@/lib/api";
import { openEventStream, type SseMessage } from "@/lib/api/sse";
import { forceRefresh, getValidToken } from "@/lib/auth/keycloak";
import { NotificationIcon } from "@/components/notifications/notification-item";
import { notificationTarget } from "@/lib/notifications";
import { useAuthStore } from "@/store/auth-store";
import { useUiStore } from "@/store/ui-store";
import type { AppNotification } from "@/types/notifications";

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function isNotification(value: unknown): value is AppNotification {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Partial<AppNotification>;
  return typeof candidate.id === "string" && typeof candidate.title === "string" && typeof candidate.type === "string";
}

/** Query prefixes that a notification type makes stale (the pushed change happened elsewhere). */
function staleKeys(notification: AppNotification): readonly (readonly unknown[])[] {
  const orgId = notification.organizationId;
  if (!orgId) return [];
  const keys: (readonly unknown[])[] = [queryKeys.dashboard(orgId)];
  if (notification.type.startsWith("campushire.drive.")) {
    keys.push([...queryKeys.campus(orgId), "student-drives"], queryKeys.records(orgId, "drive"));
  } else if (notification.type === "campushire.application.stage_changed") {
    keys.push(queryKeys.records(orgId, "application"), [...queryKeys.campus(orgId), "student-drives"]);
  } else if (notification.type === "campushire.offer.extended") {
    keys.push(queryKeys.records(orgId, "offer"), queryKeys.records(orgId, "application"));
  } else if (notification.type === "campushire.interview.scheduled") {
    keys.push(queryKeys.records(orgId, "interview"));
  }
  return keys;
}

/**
 * Keeps one authenticated SSE connection to `GET /notifications/stream` while signed in: live unread count, new
 * notifications prepended to cached lists, a toast per new notification, and a list refetch after reconnecting.
 */
export function NotificationStream() {
  const authed = useAuthStore((state) => state.status === "authenticated");
  const setStreamStatus = useUiStore((state) => state.setNotificationStream);
  const queryClient = useQueryClient();
  const router = useRouter();
  const { persona } = useOrg();
  const personaRef = React.useRef(persona);

  React.useEffect(() => {
    personaRef.current = persona;
  }, [persona]);

  React.useEffect(() => {
    if (!authed) return;

    const onMessage = ({ event, data }: SseMessage) => {
      const payload = parseJson(data);
      if (event === "unread-count") {
        const count = (payload as { count?: unknown } | undefined)?.count;
        if (typeof count === "number") setUnreadCount(queryClient, count);
        return;
      }
      if (event !== "notification" || !isNotification(payload)) return;
      prependNotification(queryClient, payload);
      for (const key of staleKeys(payload)) void queryClient.invalidateQueries({ queryKey: key });
      if (payload.read) return;
      const target = notificationTarget(payload, personaRef.current);
      toast(payload.title, {
        id: `notification-${payload.id}`,
        description: payload.body ?? undefined,
        icon: <NotificationIcon type={payload.type} className="size-4 text-primary" />,
        action: target
          ? {
              label: "Open",
              onClick: () => {
                if (target.external) window.open(target.href, "_blank", "noopener,noreferrer");
                else router.push(target.href);
              },
            }
          : undefined,
      });
    };

    const handle = openEventStream({
      path: "/notifications/stream",
      getToken: (force) => (force ? forceRefresh() : getValidToken(30)),
      onMessage,
      onOpen: ({ reconnect }) => {
        if (reconnect) void queryClient.invalidateQueries({ queryKey: [...queryKeys.notifications(), "list"] });
      },
      onStatus: setStreamStatus,
    });

    const reconnect = () => handle.reconnectNow();
    window.addEventListener("online", reconnect);
    return () => {
      window.removeEventListener("online", reconnect);
      handle.close();
      setStreamStatus("idle");
    };
  }, [authed, queryClient, router, setStreamStatus]);

  return null;
}
