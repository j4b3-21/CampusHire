"use client";

import * as React from "react";
import Link from "next/link";
import { Bell, BellOff, CheckCheck } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { NotificationItem, NotificationSkeletons } from "@/components/notifications/notification-item";
import { ErrorState } from "@/components/common/error-state";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { useMarkAllNotificationsRead, useNotifications, useUnreadCount } from "@/lib/api";
import { NOTIFICATIONS_NAV, orgPath } from "@/lib/navigation";
import { cn } from "@/lib/utils";
import { useUiStore } from "@/store/ui-store";

const STREAM_LABEL = {
  idle: "Live updates off",
  connecting: "Connecting…",
  open: "Live",
  reconnecting: "Reconnecting…",
  closed: "Live updates off",
} as const;

export function NotificationsPopover() {
  const { orgId } = useOrg();
  const [open, setOpen] = React.useState(false);
  const unread = useUnreadCount();
  const list = useNotifications({ enabled: open });
  const markAll = useMarkAllNotificationsRead();
  const stream = useUiStore((state) => state.notificationStream);
  const count = unread.data ?? 0;
  const items = (list.data?.pages ?? []).flatMap((page) => page.items).slice(0, 12);

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          variant="ghost"
          size="icon-sm"
          className="relative"
          aria-label={count > 0 ? `Notifications, ${count} unread` : "Notifications"}
        >
          <Bell />
          {count > 0 ? (
            <span
              aria-hidden
              className="absolute -right-0.5 -top-0.5 grid h-4 min-w-4 place-items-center rounded-full bg-primary px-1 text-[10px] font-semibold leading-none text-primary-foreground tabular ring-2 ring-background animate-pop-in"
            >
              {count > 99 ? "99+" : count}
            </span>
          ) : null}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="flex w-[min(24rem,calc(100vw-1.5rem))] flex-col p-0">
        <div className="flex items-center gap-2 border-b border-border px-4 py-2.5">
          <h2 className="text-[13px] font-semibold">Notifications</h2>
          <SimpleTooltip label={STREAM_LABEL[stream]}>
            <span
              className={cn(
                "size-1.5 rounded-full",
                stream === "open" ? "bg-success" : stream === "reconnecting" || stream === "connecting" ? "bg-warning" : "bg-subtle-foreground"
              )}
              aria-label={STREAM_LABEL[stream]}
              role="img"
            />
          </SimpleTooltip>
          {count > 0 ? <span className="text-xs text-muted-foreground tabular">{count} unread</span> : null}
          <Button
            variant="subtle"
            size="xs"
            className="ml-auto"
            disabled={count === 0 || markAll.isPending}
            onClick={() => markAll.mutate()}
          >
            <CheckCheck /> Mark all read
          </Button>
        </div>
        <div className="max-h-[min(28rem,70dvh)] overflow-y-auto">
          {list.isLoading ? (
            <NotificationSkeletons count={3} />
          ) : list.isError ? (
            <ErrorState size="sm" error={list.error} onRetry={() => void list.refetch()} />
          ) : items.length === 0 ? (
            <EmptyState
              size="sm"
              icon={BellOff}
              title="You're all caught up"
              description="Drive announcements, stage changes, offers and interview schedules show up here."
            />
          ) : (
            <ul className="flex flex-col divide-y divide-border" aria-label="Recent notifications">
              {items.map((notification) => (
                <NotificationItem key={notification.id} notification={notification} dense onNavigate={() => setOpen(false)} />
              ))}
            </ul>
          )}
        </div>
        <div className="border-t border-border p-1.5">
          <Button variant="ghost" size="sm" className="w-full" asChild>
            <Link href={orgPath(orgId, NOTIFICATIONS_NAV.path)} onClick={() => setOpen(false)}>
              View all notifications
            </Link>
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  );
}
