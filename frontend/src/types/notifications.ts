import type { Instant, JsonObject, Uuid } from "@/types/api";

/** One in-app notification; `title` and `body` are plain text. */
export interface AppNotification {
  id: Uuid;
  /** `null` for platform-level notifications. */
  organizationId: Uuid | null;
  type: string;
  title: string;
  body: string | null;
  /** Absolute app path (`/app/<orgId>/...`) or an `http(s)` URL. */
  link: string | null;
  data: JsonObject | null;
  read: boolean;
  readAt: Instant | null;
  createdAt: Instant;
}

export interface UnreadCount {
  count: number;
}

export type StreamStatus = "idle" | "connecting" | "open" | "reconnecting" | "closed";
