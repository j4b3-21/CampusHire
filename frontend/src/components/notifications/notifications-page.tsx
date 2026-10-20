"use client";

import * as React from "react";
import { BellOff, CheckCheck } from "lucide-react";

import { NotificationItem, NotificationSkeletons } from "@/components/notifications/notification-item";
import { ErrorState } from "@/components/common/error-state";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Spinner } from "@/components/ui/spinner";
import { SegmentedList, SegmentedTrigger, Tabs } from "@/components/ui/tabs";
import { useMarkAllNotificationsRead, useNotifications, useUnreadCount } from "@/lib/api";
import { formatInteger } from "@/lib/metadata/format";

export function NotificationsPage() {
  const [filter, setFilter] = React.useState<"all" | "unread">("all");
  const list = useNotifications({ unreadOnly: filter === "unread" });
  const unread = useUnreadCount();
  const markAll = useMarkAllNotificationsRead();
  const items = (list.data?.pages ?? []).flatMap((page) => page.items);
  const total = list.data?.pages[0]?.totalElements ?? 0;
  const count = unread.data ?? 0;

  return (
    <>
      <PageHeader
        title="Notifications"
        description="Updates about drives, applications, offers and interviews. New ones arrive live."
        actions={
          <Button variant="outline" disabled={count === 0 || markAll.isPending} onClick={() => markAll.mutate()}>
            <CheckCheck /> Mark all as read
          </Button>
        }
      />
      <PageBody className="max-w-3xl">
        <Tabs value={filter} onValueChange={(value) => setFilter(value === "unread" ? "unread" : "all")}>
          <div className="mb-3 flex items-center justify-between gap-3">
            <SegmentedList aria-label="Filter notifications">
              <SegmentedTrigger value="all">All</SegmentedTrigger>
              <SegmentedTrigger value="unread">
                Unread{count > 0 ? <span className="tabular text-muted-foreground">{formatInteger(count)}</span> : null}
              </SegmentedTrigger>
            </SegmentedList>
            {list.data ? (
              <span className="text-xs text-muted-foreground tabular">
                {formatInteger(total)} {total === 1 ? "notification" : "notifications"}
              </span>
            ) : null}
          </div>
        </Tabs>
        <div className="overflow-hidden rounded-lg border border-border bg-card">
          {list.isLoading ? (
            <NotificationSkeletons count={6} />
          ) : list.isError ? (
            <ErrorState error={list.error} onRetry={() => void list.refetch()} />
          ) : items.length === 0 ? (
            <EmptyState
              icon={BellOff}
              title={filter === "unread" ? "No unread notifications" : "No notifications yet"}
              description="Drive announcements, stage changes, offers and interview schedules show up here."
            />
          ) : (
            <ul className="flex flex-col divide-y divide-border" aria-label="Notifications">
              {items.map((notification) => (
                <NotificationItem key={notification.id} notification={notification} />
              ))}
            </ul>
          )}
        </div>
        {list.hasNextPage ? (
          <div className="mt-4 flex justify-center">
            <Button variant="outline" size="sm" disabled={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
              {list.isFetchingNextPage ? <Spinner /> : null}
              Load older notifications
            </Button>
          </div>
        ) : null}
      </PageBody>
    </>
  );
}
