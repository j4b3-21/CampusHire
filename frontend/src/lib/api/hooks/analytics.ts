"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { downloadBlob } from "@/lib/api/download";
import { queryKeys } from "@/lib/api/keys";
import { useAuthStore } from "@/store/auth-store";
import type {
  AnalyticsCompanies,
  AnalyticsDepartments,
  AnalyticsFilters,
  AnalyticsOverview,
  AnalyticsTrend,
  CtcDistribution,
  DriveFunnel,
  ReportFormat,
} from "@/types/analytics";

const base = (orgId: string) => `/orgs/${orgId}/campushire/analytics`;

function filterQuery(filters: AnalyticsFilters) {
  return { batchId: filters.batchId, departmentId: filters.departmentId, from: filters.from, to: filters.to };
}

function useAnalyticsPart<T>(
  orgId: string | undefined,
  part: string,
  path: string,
  filters: AnalyticsFilters,
  extra: Record<string, string | number | undefined> = {},
  options: { enabled?: boolean } = {}
) {
  const authed = useAuthStore((state) => state.status === "authenticated");
  return useQuery({
    queryKey: queryKeys.analyticsPart(orgId ?? "", part, { ...filters, ...extra }),
    queryFn: ({ signal }) => api.get<T>(`${base(orgId!)}${path}`, { query: { ...filterQuery(filters), ...extra }, signal }),
    enabled: authed && Boolean(orgId) && options.enabled !== false,
    placeholderData: keepPreviousData,
    staleTime: 60_000,
    meta: { silent: true },
  });
}

export function useAnalyticsOverview(orgId: string | undefined, filters: AnalyticsFilters, options?: { enabled?: boolean }) {
  return useAnalyticsPart<AnalyticsOverview>(orgId, "overview", "/overview", filters, {}, options);
}

export function useAnalyticsDepartments(orgId: string | undefined, filters: AnalyticsFilters) {
  return useAnalyticsPart<AnalyticsDepartments>(orgId, "departments", "/departments", filters);
}

export function useAnalyticsCompanies(orgId: string | undefined, filters: AnalyticsFilters, limit = 10) {
  return useAnalyticsPart<AnalyticsCompanies>(orgId, "companies", "/companies", filters, { limit });
}

export function useCtcDistribution(orgId: string | undefined, filters: AnalyticsFilters) {
  return useAnalyticsPart<CtcDistribution>(orgId, "ctc", "/ctc-distribution", filters);
}

export function useAnalyticsTrend(orgId: string | undefined, filters: AnalyticsFilters) {
  return useAnalyticsPart<AnalyticsTrend>(orgId, "trend", "/trend", filters, { interval: "month" });
}

export function useDriveFunnel(orgId: string | undefined, driveId: string | null, filters: AnalyticsFilters) {
  return useAnalyticsPart<DriveFunnel>(orgId, `funnel-${driveId}`, `/drives/${driveId}/funnel`, filters, {}, {
    enabled: Boolean(driveId),
  });
}

export function downloadPlacementReport(orgId: string, format: ReportFormat, filters: AnalyticsFilters) {
  return downloadBlob(`/orgs/${orgId}/campushire/reports/placement`, {
    query: { format, ...filterQuery(filters) },
    fallbackName: `placement-report.${format}`,
  });
}
