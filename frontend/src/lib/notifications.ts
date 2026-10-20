import { Award, Bell, CalendarRange, CalendarX2, GitBranch, MessagesSquare, type LucideIcon } from "lucide-react";

import type { Persona } from "@/types/campushire";
import type { AppNotification } from "@/types/notifications";

const TYPE_ICONS: Record<string, LucideIcon> = {
  "campushire.drive.published": CalendarRange,
  "campushire.drive.cancelled": CalendarX2,
  "campushire.application.stage_changed": GitBranch,
  "campushire.offer.extended": Award,
  "campushire.interview.scheduled": MessagesSquare,
};

export function notificationIcon(type: string): LucideIcon {
  return TYPE_ICONS[type] ?? Bell;
}

export interface NotificationTarget {
  href: string;
  external: boolean;
}

function dataString(notification: AppNotification, key: string) {
  const value = notification.data?.[key];
  return typeof value === "string" ? value : undefined;
}

/**
 * Where a notification leads. Students land on their portal pages instead of the generic record pages; external
 * links open in a new tab. `null` when the notification has no usable link.
 */
export function notificationTarget(notification: AppNotification, persona: Persona | undefined): NotificationTarget | null {
  const link = notification.link?.trim();
  if (link && /^https?:\/\//i.test(link)) return { href: link, external: true };
  const orgId = notification.organizationId;
  if (persona === "student" && orgId) {
    switch (notification.type) {
      case "campushire.drive.published":
      case "campushire.drive.cancelled": {
        const driveId = dataString(notification, "driveId");
        return { href: `/app/${orgId}/student/drives?tab=all${driveId ? `&drive=${driveId}` : ""}`, external: false };
      }
      case "campushire.application.stage_changed":
        return { href: `/app/${orgId}/student/applications`, external: false };
      case "campushire.offer.extended":
        return { href: `/app/${orgId}/student/offers`, external: false };
    }
  }
  if (link && link.startsWith("/") && !link.startsWith("//")) return { href: link, external: false };
  return null;
}
