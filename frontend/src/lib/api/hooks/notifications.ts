"use client";

import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type InfiniteData } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { useAuthStore } from "@/store/auth-store";
import type { Page } from "@/types/api";
import type { AppNotification, UnreadCount } from "@/types/notifications";

export const NOTIFICATION_PAGE_SIZE = 20;

type NotificationPages = InfiniteData<Page<AppNotification>, number>;

function useAuthed() {
  return useAuthStore((state) => state.status === "authenticated");
}

/** Newest first, page by page (`GET /notifications[?unread=true]`). */
export function useNotifications(options: { unreadOnly?: boolean; enabled?: boolean } = {}) {
  const authed = useAuthed();
  const unreadOnly = options.unreadOnly ?? false;
  return useInfiniteQuery({
    queryKey: queryKeys.notificationList(unreadOnly),
    queryFn: ({ pageParam, signal }) =>
      api.get<Page<AppNotification>>("/notifications", {
        query: { page: pageParam, size: NOTIFICATION_PAGE_SIZE, unread: unreadOnly ? true : undefined },
        signal,
      }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
    enabled: authed && options.enabled !== false,
    staleTime: 60_000,
    meta: { silent: true },
  });
}

export function useUnreadCount() {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.unreadCount(),
    queryFn: ({ signal }) => api.get<UnreadCount>("/notifications/unread-count", { signal }),
    enabled: authed,
    staleTime: 5 * 60_000,
    select: (data) => data.count,
    meta: { silent: true },
  });
}

function patchNotifications(
  queryClient: ReturnType<typeof useQueryClient>,
  update: (item: AppNotification) => AppNotification
) {
  queryClient.setQueriesData<NotificationPages>({ queryKey: [...queryKeys.notifications(), "list"] }, (data) =>
    data ? { ...data, pages: data.pages.map((page) => ({ ...page, items: page.items.map(update) })) } : data
  );
}

/** Adds a pushed notification to the top of the cached lists (deduplicated by id). */
export function prependNotification(queryClient: ReturnType<typeof useQueryClient>, notification: AppNotification) {
  queryClient.setQueriesData<NotificationPages>({ queryKey: [...queryKeys.notifications(), "list"] }, (data) => {
    if (!data || data.pages.length === 0) return data;
    if (data.pages.some((page) => page.items.some((item) => item.id === notification.id))) return data;
    const [first, ...rest] = data.pages;
    return {
      ...data,
      pages: [{ ...first, items: [notification, ...first.items], totalElements: first.totalElements + 1 }, ...rest],
    };
  });
}

export function setUnreadCount(queryClient: ReturnType<typeof useQueryClient>, count: number) {
  queryClient.setQueryData<UnreadCount>(queryKeys.unreadCount(), { count });
}

export function useMarkNotificationRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.post<AppNotification>(`/notifications/${id}/read`),
    onMutate: (id) => {
      let changed = false;
      patchNotifications(queryClient, (item) => {
        if (item.id !== id || item.read) return item;
        changed = true;
        return { ...item, read: true, readAt: new Date().toISOString() };
      });
      if (changed) {
        const current = queryClient.getQueryData<UnreadCount>(queryKeys.unreadCount());
        if (current) setUnreadCount(queryClient, Math.max(0, current.count - 1));
      }
    },
    meta: { silent: true },
    onError: () => void queryClient.invalidateQueries({ queryKey: queryKeys.notifications() }),
  });
}

export function useMarkAllNotificationsRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<{ updated: number }>("/notifications/read-all"),
    onMutate: () => {
      const now = new Date().toISOString();
      patchNotifications(queryClient, (item) => (item.read ? item : { ...item, read: true, readAt: now }));
      setUnreadCount(queryClient, 0);
    },
    meta: { errorTitle: "Couldn't mark notifications as read" },
    onError: () => void queryClient.invalidateQueries({ queryKey: queryKeys.notifications() }),
  });
}
