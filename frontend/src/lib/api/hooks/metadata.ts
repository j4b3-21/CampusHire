"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { useAuthStore } from "@/store/auth-store";
import type { ModuleDefinition, RelationshipDefinition } from "@/types/metadata";

export function useModules(orgId: string | undefined) {
  const authed = useAuthStore((state) => state.status === "authenticated");
  return useQuery({
    queryKey: queryKeys.modules(orgId ?? ""),
    queryFn: ({ signal }) => api.get<ModuleDefinition[]>(`/orgs/${orgId}/modules`, { signal }),
    enabled: authed && Boolean(orgId),
    staleTime: 5 * 60_000,
  });
}

export function useModule(orgId: string | undefined, moduleKey: string | undefined) {
  const authed = useAuthStore((state) => state.status === "authenticated");
  const queryClient = useQueryClient();
  return useQuery({
    queryKey: queryKeys.module(orgId ?? "", moduleKey ?? ""),
    queryFn: ({ signal }) => api.get<ModuleDefinition>(`/orgs/${orgId}/modules/${moduleKey}`, { signal }),
    enabled: authed && Boolean(orgId && moduleKey),
    staleTime: 5 * 60_000,
    initialData: () =>
      queryClient
        .getQueryData<ModuleDefinition[]>(queryKeys.modules(orgId ?? ""))
        ?.find((module) => module.apiKey === moduleKey),
    initialDataUpdatedAt: () => queryClient.getQueryState(queryKeys.modules(orgId ?? ""))?.dataUpdatedAt,
  });
}

export function useRelationships(orgId: string | undefined) {
  const authed = useAuthStore((state) => state.status === "authenticated");
  return useQuery({
    queryKey: queryKeys.relationships(orgId ?? ""),
    queryFn: ({ signal }) => api.get<RelationshipDefinition[]>(`/orgs/${orgId}/relationships`, { signal }),
    enabled: authed && Boolean(orgId),
    staleTime: 5 * 60_000,
    meta: { silent: true },
  });
}
