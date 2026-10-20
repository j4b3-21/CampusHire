"use client";

import {
  keepPreviousData,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
} from "@tanstack/react-query";

import { api, request } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { queryKeys } from "@/lib/api/keys";
import { recordQueryString } from "@/lib/api/record-query";
import type { VersionedRecord } from "@/lib/api/hooks/records";
import { useAuthStore } from "@/store/auth-store";
import type { Page } from "@/types/api";
import type { RecordItem, RecordQueryParams } from "@/types/records";
import type {
  AvailableTransitions,
  BulkTransitionResult,
  TransitionHistoryItem,
  TransitionOutcome,
  Workflow,
  WorkflowDefinitionBody,
} from "@/types/workflow";

function useAuthed() {
  return useAuthStore((state) => state.status === "authenticated");
}

function workflowPath(orgId: string, moduleKey: string) {
  return `/orgs/${orgId}/modules/${moduleKey}/workflow`;
}

function recordPath(orgId: string, moduleKey: string, id: string) {
  return `/orgs/${orgId}/modules/${moduleKey}/records/${id}`;
}

/** The module's workflow, or `null` when it has none (404). */
export function useWorkflow(orgId: string | undefined, moduleKey: string | undefined, options: { enabled?: boolean } = {}) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.workflow(orgId ?? "", moduleKey ?? ""),
    queryFn: async ({ signal }): Promise<Workflow | null> => {
      try {
        return await api.get<Workflow>(workflowPath(orgId!, moduleKey!), { signal });
      } catch (error) {
        if (error instanceof ApiError && error.isNotFound) return null;
        throw error;
      }
    },
    enabled: authed && Boolean(orgId && moduleKey) && options.enabled !== false,
    staleTime: 5 * 60_000,
    meta: { silent: true },
  });
}

export interface SaveWorkflowInput {
  field: string;
  definition: WorkflowDefinitionBody;
  /** Current version; omit to create. */
  ifMatch?: number | null;
}

export function useSaveWorkflow(orgId: string, moduleKey: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ field, definition, ifMatch }: SaveWorkflowInput) =>
      api.put<Workflow>(workflowPath(orgId, moduleKey), { field, definition }, { ifMatch: ifMatch ?? undefined }),
    meta: { handlesValidation: true, errorTitle: "Couldn't save the workflow" },
    onSuccess: (workflow) => {
      queryClient.setQueryData(queryKeys.workflow(orgId, moduleKey), workflow);
      void queryClient.invalidateQueries({ queryKey: queryKeys.records(orgId, moduleKey) });
    },
  });
}

export function useDeleteWorkflow(orgId: string, moduleKey: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (version: number) => api.delete<void>(workflowPath(orgId, moduleKey), { ifMatch: version, responseType: "none" }),
    meta: { errorTitle: "Couldn't remove the workflow" },
    onSuccess: () => {
      queryClient.setQueryData(queryKeys.workflow(orgId, moduleKey), null);
    },
  });
}

export const BOARD_PAGE_SIZE = 20;

export type BoardColumnData = InfiniteData<Page<RecordItem>, number>;

/** Board column: records in `state` (`filter[field]=state` plus the board filters), loaded page by page. */
export function useBoardColumn(
  orgId: string,
  moduleKey: string,
  field: string,
  state: string,
  params: Pick<RecordQueryParams, "q" | "filters">,
  options: { enabled?: boolean } = {}
) {
  const authed = useAuthed();
  return useInfiniteQuery({
    queryKey: queryKeys.boardColumn(orgId, moduleKey, state, params),
    queryFn: ({ pageParam, signal }) =>
      api.get<Page<RecordItem>>(`/orgs/${orgId}/modules/${moduleKey}/records`, {
        query: recordQueryString({
          page: pageParam,
          size: BOARD_PAGE_SIZE,
          sort: "-updated_at",
          q: params.q,
          filters: [...(params.filters ?? []), { field, op: "eq", value: state }],
        }),
        signal,
      }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
    enabled: authed && options.enabled !== false,
    staleTime: 15_000,
    placeholderData: keepPreviousData,
    meta: { silent: true },
  });
}

export function useAvailableTransitions(
  orgId: string | undefined,
  moduleKey: string | undefined,
  id: string | undefined,
  options: { enabled?: boolean } = {}
) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.available(orgId ?? "", moduleKey ?? "", id ?? ""),
    queryFn: ({ signal }) =>
      api.get<AvailableTransitions>(`${recordPath(orgId!, moduleKey!, id!)}/transitions/available`, { signal }),
    enabled: authed && Boolean(orgId && moduleKey && id) && options.enabled !== false,
    staleTime: 15_000,
    meta: { silent: true },
  });
}

export function useTransitionHistory(
  orgId: string | undefined,
  moduleKey: string | undefined,
  id: string | undefined,
  options: { enabled?: boolean; size?: number } = {}
) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.transitions(orgId ?? "", moduleKey ?? "", id ?? ""),
    queryFn: ({ signal }) =>
      api.get<Page<TransitionHistoryItem>>(`${recordPath(orgId!, moduleKey!, id!)}/transitions`, {
        query: { size: options.size ?? 50 },
        signal,
      }),
    enabled: authed && Boolean(orgId && moduleKey && id) && options.enabled !== false,
    staleTime: 15_000,
    meta: { silent: true },
  });
}

export interface TransitionInput {
  id: string;
  to: string;
  comment?: string;
  ifMatch: number | string;
}

export function useTransitionRecord(orgId: string, moduleKey: string, options: { silent?: boolean } = {}) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, to, comment, ifMatch }: TransitionInput) => {
      const result = await request<TransitionOutcome>(`${recordPath(orgId, moduleKey, id)}/transitions`, {
        method: "POST",
        body: { to, comment: comment?.trim() ? comment.trim() : undefined },
        ifMatch,
      });
      const record: VersionedRecord = { ...result.data.record, etag: result.etag ?? `"${result.data.record.version}"` };
      return { record, transition: result.data.transition };
    },
    meta: { silent: options.silent, errorTitle: "Couldn't move the record" },
    onSuccess: ({ record }) => {
      queryClient.setQueryData(queryKeys.record(orgId, moduleKey, record.id), record);
      const base = queryKeys.records(orgId, moduleKey);
      void queryClient.invalidateQueries({ queryKey: [...base, "available", record.id] });
      void queryClient.invalidateQueries({ queryKey: [...base, "transitions", record.id] });
      void queryClient.invalidateQueries({ queryKey: [...base, "versions", record.id] });
      void queryClient.invalidateQueries({ queryKey: [...base, "list"] });
    },
  });
}

export function useBulkTransition(orgId: string, moduleKey: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ recordIds, to, comment }: { recordIds: string[]; to: string; comment?: string }) =>
      api.post<BulkTransitionResult>(`/orgs/${orgId}/modules/${moduleKey}/transitions/bulk`, {
        recordIds,
        to,
        comment: comment?.trim() ? comment.trim() : undefined,
      }),
    meta: { errorTitle: "Couldn't move the records" },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.records(orgId, moduleKey) });
    },
  });
}
