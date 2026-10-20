"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { downloadBlob } from "@/lib/api/download";
import { queryKeys } from "@/lib/api/keys";
import { appendFilters } from "@/lib/api/record-query";
import { useAuthStore } from "@/store/auth-store";
import type { Page } from "@/types/api";
import {
  FINISHED_IMPORT_STATUSES,
  type ExchangeFormat,
  type ImportJob,
  type ImportOptions,
  type ImportPreview,
} from "@/types/data-exchange";
import type { RecordQueryParams } from "@/types/records";

const modulePath = (orgId: string, moduleKey: string) => `/orgs/${orgId}/modules/${moduleKey}`;

export function downloadImportTemplate(orgId: string, moduleKey: string, format: ExchangeFormat) {
  return downloadBlob(`${modulePath(orgId, moduleKey)}/import-template`, {
    query: { format },
    fallbackName: `${moduleKey}-template.${format}`,
  });
}

export function downloadImportErrors(orgId: string, moduleKey: string, jobId: string, format: ExchangeFormat) {
  return downloadBlob(`${modulePath(orgId, moduleKey)}/imports/${jobId}/errors`, {
    query: { format },
    fallbackName: `${moduleKey}-import-errors.${format}`,
  });
}

/** `GET .../export` with the list's filters, sort and search (all pages, the caller's scope). */
export function exportRecords(
  orgId: string,
  moduleKey: string,
  format: ExchangeFormat,
  params: Pick<RecordQueryParams, "sort" | "q" | "filters">
) {
  const query = new URLSearchParams({ format });
  if (params.sort) query.set("sort", params.sort);
  if (params.q?.trim()) query.set("q", params.q.trim());
  appendFilters(query, params.filters);
  return downloadBlob(`${modulePath(orgId, moduleKey)}/export`, { query, fallbackName: `${moduleKey}.${format}` });
}

export function usePreviewImport(orgId: string, moduleKey: string) {
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData();
      form.append("file", file);
      return api.post<ImportPreview>(`${modulePath(orgId, moduleKey)}/imports/preview`, form);
    },
    meta: { silent: true },
  });
}

export function useStartImport(orgId: string, moduleKey: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ file, options }: { file: File; options: ImportOptions }) => {
      const form = new FormData();
      form.append("file", file);
      form.append("options", new Blob([JSON.stringify(options)], { type: "application/json" }));
      return api.post<ImportJob>(`${modulePath(orgId, moduleKey)}/imports`, form);
    },
    meta: { silent: true },
    onSuccess: async (job) => {
      queryClient.setQueryData(queryKeys.importJob(orgId, moduleKey, job.id), job);
      await queryClient.invalidateQueries({ queryKey: [...queryKeys.imports(orgId, moduleKey), "list"] });
    },
  });
}

/** Polls the job every second until it is finished; then refreshes the module's record lists. */
export function useImportJob(orgId: string, moduleKey: string, jobId: string | null) {
  const authed = useAuthStore((state) => state.status === "authenticated");
  const queryClient = useQueryClient();
  return useQuery({
    queryKey: queryKeys.importJob(orgId, moduleKey, jobId ?? ""),
    queryFn: async ({ signal }) => {
      const job = await api.get<ImportJob>(`${modulePath(orgId, moduleKey)}/imports/${jobId}`, { signal });
      if (FINISHED_IMPORT_STATUSES.includes(job.status)) {
        void queryClient.invalidateQueries({ queryKey: [...queryKeys.imports(orgId, moduleKey), "list"] });
        if (!job.dryRun) void queryClient.invalidateQueries({ queryKey: queryKeys.records(orgId, moduleKey) });
      }
      return job;
    },
    enabled: authed && Boolean(jobId),
    refetchInterval: (query) =>
      query.state.data && FINISHED_IMPORT_STATUSES.includes(query.state.data.status) ? false : 1_000,
    refetchIntervalInBackground: true,
    meta: { silent: true },
  });
}

export function useImportJobs(orgId: string, moduleKey: string | undefined, page = 0) {
  const authed = useAuthStore((state) => state.status === "authenticated");
  return useQuery({
    queryKey: [...queryKeys.imports(orgId, moduleKey ?? ""), "list", page],
    queryFn: ({ signal }) =>
      api.get<Page<ImportJob>>(`${modulePath(orgId, moduleKey!)}/imports`, { query: { page, size: 10 }, signal }),
    enabled: authed && Boolean(moduleKey),
    placeholderData: keepPreviousData,
    meta: { silent: true },
  });
}

export function useCancelImport(orgId: string, moduleKey: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (jobId: string) => api.post<ImportJob>(`${modulePath(orgId, moduleKey)}/imports/${jobId}/cancel`),
    meta: { errorTitle: "Couldn't cancel the import" },
    onSuccess: (job) => {
      queryClient.setQueryData(queryKeys.importJob(orgId, moduleKey, job.id), job);
    },
  });
}
