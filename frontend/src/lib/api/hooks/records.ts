"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api, request } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { recordQueryString } from "@/lib/api/record-query";
import { useAuthStore } from "@/store/auth-store";
import type { Page } from "@/types/api";
import type { RecordData, RecordItem, RecordQueryParams, RecordVersion, RelatedRecord } from "@/types/records";

const MERGE_PATCH = "application/merge-patch+json";

function recordsPath(orgId: string, moduleKey: string) {
  return `/orgs/${orgId}/modules/${moduleKey}/records`;
}

/** A record together with the `ETag` it was served with (falls back to its version). */
export type VersionedRecord = RecordItem & { etag: string };

function withETag(record: RecordItem, etag: string | null): VersionedRecord {
  return { ...record, etag: etag ?? `"${record.version}"` };
}

function useAuthed() {
  return useAuthStore((state) => state.status === "authenticated");
}

export function useRecords(
  orgId: string | undefined,
  moduleKey: string | undefined,
  params: RecordQueryParams,
  options: { enabled?: boolean } = {}
) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.recordList(orgId ?? "", moduleKey ?? "", params),
    queryFn: ({ signal }) =>
      api.get<Page<RecordItem>>(recordsPath(orgId!, moduleKey!), { query: recordQueryString(params), signal }),
    enabled: authed && Boolean(orgId && moduleKey) && options.enabled !== false,
    placeholderData: keepPreviousData,
    staleTime: 15_000,
  });
}

export function useRecord(
  orgId: string | undefined,
  moduleKey: string | undefined,
  id: string | undefined,
  options: { enabled?: boolean; silent?: boolean } = {}
) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.record(orgId ?? "", moduleKey ?? "", id ?? ""),
    queryFn: async ({ signal }) => {
      const result = await request<RecordItem>(`${recordsPath(orgId!, moduleKey!)}/${id}`, { signal });
      return withETag(result.data, result.etag);
    },
    enabled: authed && Boolean(orgId && moduleKey && id) && options.enabled !== false,
    staleTime: 30_000,
    meta: { silent: options.silent },
  });
}

function useRecordCache(orgId: string, moduleKey: string) {
  const queryClient = useQueryClient();
  return {
    put(record: VersionedRecord) {
      queryClient.setQueryData(queryKeys.record(orgId, moduleKey, record.id), record);
    },
    async invalidateLists() {
      await queryClient.invalidateQueries({ queryKey: [...queryKeys.records(orgId, moduleKey), "list"] });
    },
    async invalidateHistory(id: string) {
      await queryClient.invalidateQueries({ queryKey: [...queryKeys.records(orgId, moduleKey), "versions", id] });
    },
  };
}

export function useCreateRecord(orgId: string, moduleKey: string) {
  const cache = useRecordCache(orgId, moduleKey);
  return useMutation({
    mutationFn: async (data: RecordData) => {
      const result = await request<RecordItem>(recordsPath(orgId, moduleKey), { method: "POST", body: { data } });
      return withETag(result.data, result.etag);
    },
    meta: { handlesValidation: true, errorTitle: "Couldn't create the record" },
    onSuccess: async (record) => {
      cache.put(record);
      await cache.invalidateLists();
    },
  });
}

export interface PatchRecordInput {
  id: string;
  /** Version or ETag the change is based on (optimistic concurrency). */
  ifMatch: number | string;
  /** JSON Merge Patch of `data`: present keys are set, `null` clears. */
  data: RecordData;
}

export function usePatchRecord(orgId: string, moduleKey: string) {
  const cache = useRecordCache(orgId, moduleKey);
  return useMutation({
    mutationFn: async ({ id, ifMatch, data }: PatchRecordInput) => {
      const result = await request<RecordItem>(`${recordsPath(orgId, moduleKey)}/${id}`, {
        method: "PATCH",
        body: { data },
        contentType: MERGE_PATCH,
        ifMatch,
      });
      return withETag(result.data, result.etag);
    },
    meta: { handlesValidation: true, errorTitle: "Couldn't save changes" },
    onSuccess: async (record) => {
      cache.put(record);
      await Promise.all([cache.invalidateLists(), cache.invalidateHistory(record.id)]);
    },
  });
}

export function useDeleteRecord(orgId: string, moduleKey: string) {
  const cache = useRecordCache(orgId, moduleKey);
  return useMutation({
    mutationFn: async ({ id, ifMatch }: { id: string; ifMatch: number | string }) => {
      const result = await request<RecordItem>(`${recordsPath(orgId, moduleKey)}/${id}`, {
        method: "DELETE",
        ifMatch,
      });
      return withETag(result.data, result.etag);
    },
    meta: { errorTitle: "Couldn't delete the record" },
    onSuccess: async (record) => {
      cache.put(record);
      await Promise.all([cache.invalidateLists(), cache.invalidateHistory(record.id)]);
    },
  });
}

export function useRestoreRecord(orgId: string, moduleKey: string) {
  const cache = useRecordCache(orgId, moduleKey);
  return useMutation({
    mutationFn: async ({ id, ifMatch }: { id: string; ifMatch: number | string }) => {
      const result = await request<RecordItem>(`${recordsPath(orgId, moduleKey)}/${id}/restore`, {
        method: "POST",
        ifMatch,
      });
      return withETag(result.data, result.etag);
    },
    meta: { errorTitle: "Couldn't restore the record" },
    onSuccess: async (record) => {
      cache.put(record);
      await Promise.all([cache.invalidateLists(), cache.invalidateHistory(record.id)]);
    },
  });
}

/** Makes the values of an earlier version current again (creates a new version). */
export function useRevertRecordVersion(orgId: string, moduleKey: string) {
  const cache = useRecordCache(orgId, moduleKey);
  return useMutation({
    mutationFn: async ({ id, version, ifMatch }: { id: string; version: number; ifMatch: number | string }) => {
      const result = await request<RecordItem>(`${recordsPath(orgId, moduleKey)}/${id}/versions/${version}/restore`, {
        method: "POST",
        ifMatch,
      });
      return withETag(result.data, result.etag);
    },
    meta: { errorTitle: "Couldn't restore that version" },
    onSuccess: async (record) => {
      cache.put(record);
      await Promise.all([cache.invalidateLists(), cache.invalidateHistory(record.id)]);
    },
  });
}

export function useRecordVersions(
  orgId: string | undefined,
  moduleKey: string | undefined,
  id: string | undefined,
  page = 0,
  size = 50
) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.versions(orgId ?? "", moduleKey ?? "", id ?? "", page),
    queryFn: ({ signal }) =>
      api.get<Page<RecordVersion>>(`${recordsPath(orgId!, moduleKey!)}/${id}/versions`, {
        query: { page, size },
        signal,
      }),
    enabled: authed && Boolean(orgId && moduleKey && id),
    placeholderData: keepPreviousData,
  });
}

export function useRelated(
  orgId: string | undefined,
  moduleKey: string | undefined,
  id: string | undefined,
  relationship: string | undefined,
  direction: "outgoing" | "incoming" = "outgoing",
  page = 0,
  size = 20
) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.related(orgId ?? "", moduleKey ?? "", id ?? "", relationship ?? "", direction, page),
    queryFn: ({ signal }) =>
      api.get<Page<RelatedRecord>>(`${recordsPath(orgId!, moduleKey!)}/${id}/related/${relationship}`, {
        query: { direction, page, size },
        signal,
      }),
    enabled: authed && Boolean(orgId && moduleKey && id && relationship),
    placeholderData: keepPreviousData,
  });
}
