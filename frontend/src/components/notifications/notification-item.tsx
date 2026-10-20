"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Check, ExternalLink } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { useMarkNotificationRead } from "@/lib/api";
import { formatDateTime, formatRelative } from "@/lib/metadata/format";
import { notificationIcon, notificationTarget } from "@/lib/notifications";
import { cn } from "@/lib/utils";
import type { AppNotification } from "@/types/notifications";

export function NotificationIcon({ type, className }: { type: string; className?: string }) {
  return React.createElement(notificationIcon(type), { className, "aria-hidden": true });
}

export function NotificationItem({
  notification,
  onNavigate,
  dense = false,
}: {
  notification: AppNotification;
  /** Called after following the link (e.g. to close the popover). */
  onNavigate?: () => void;
  dense?: boolean;
}) {
  const router = useRouter();
  const { persona } = useOrg();
  const markRead = useMarkNotificationRead();
  const target = notificationTarget(notification, persona);
  const unread = !notification.read;

  const open = () => {
    if (unread) markRead.mutate(notification.id);
    if (!target) return;
    if (target.external) window.open(target.href, "_blank", "noopener,noreferrer");
    else router.push(target.href);
    onNavigate?.();
  };

  return (
    <li className="group/item relative">
      <button
        type="button"
        onClick={open}
        className={cn(
          "focus-ring flex w-full items-start gap-3 text-left transition-colors hover:bg-accent/70",
          dense ? "px-4 py-3" : "rounded-lg px-4 py-3.5",
          unread && "bg-primary-soft/35"
        )}
      >
        <span
          className={cn(
            "mt-0.5 grid size-7 shrink-0 place-items-center rounded-md border",
            unread ? "border-primary/20 bg-background text-primary" : "border-border bg-surface text-muted-foreground"
          )}
        >
          <NotificationIcon type={notification.type} className="size-3.5" />
        </span>
        <span className="min-w-0 flex-1 pr-6">
          <span className={cn("block text-[13px] leading-snug", unread ? "font-semibold" : "font-medium text-foreground/85")}>
            {notification.title}
            {target?.external ? <ExternalLink className="ml-1 inline size-3 text-muted-foreground" aria-label="(opens in a new tab)" /> : null}
          </span>
          {notification.body ? (
            <span className={cn("mt-0.5 block text-[13px] leading-snug text-muted-foreground", dense && "line-clamp-2")}>
              {notification.body}
            </span>
          ) : null}
          <span className="mt-1 block text-xs text-subtle-foreground" title={formatDateTime(notification.createdAt)}>
            {formatRelative(notification.createdAt)}
          </span>
        </span>
      </button>
      {unread ? (
        <>
          <span aria-hidden className="pointer-events-none absolute right-4 top-4 size-2 rounded-full bg-primary group-hover/item:opacity-0" />
          <span className="sr-only">Unread.</span>
          <SimpleTooltip label="Mark as read">
            <Button
              variant="ghost"
              size="icon-xs"
              aria-label={`Mark “${notification.title}” as read`}
              className="absolute right-2.5 top-2.5 opacity-0 focus-visible:opacity-100 group-hover/item:opacity-100"
              onClick={() => markRead.mutate(notification.id)}
            >
              <Check />
            </Button>
          </SimpleTooltip>
        </>
      ) : null}
    </li>
  );
}

export function NotificationSkeletons({ count = 4 }: { count?: number }) {
  return (
    <ul aria-hidden className="flex flex-col">
      {Array.from({ length: count }, (_, i) => (
        <li key={i} className="flex items-start gap-3 px-4 py-3.5">
          <Skeleton className="size-7 rounded-md" />
          <div className="flex flex-1 flex-col gap-1.5">
            <Skeleton className="h-3.5 w-3/5" />
            <Skeleton className="h-3 w-4/5" />
            <Skeleton className="h-3 w-16" />
          </div>
        </li>
      ))}
    </ul>
  );
}
