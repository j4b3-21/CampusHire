"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api, request } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { useAuthStore } from "@/store/auth-store";
import type { OrgRole, OrgRoleInput } from "@/types/access";

function useAuthed() {
  return useAuthStore((state) => state.status === "authenticated");
}

/** Org roles (admins only; `METADATA_MANAGE`). */
export function useRoles(orgId: string | undefined, options: { enabled?: boolean } = {}) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.roles(orgId ?? ""),
    queryFn: ({ signal }) => api.get<OrgRole[]>(`/orgs/${orgId}/roles`, { signal }),
    enabled: authed && Boolean(orgId) && options.enabled !== false,
    staleTime: 60_000,
  });
}

export function useCreateRole(orgId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: OrgRoleInput & { apiKey: string }) => api.post<OrgRole>(`/orgs/${orgId}/roles`, input),
    meta: { handlesValidation: true, errorTitle: "Couldn't create the role" },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.roles(orgId) });
    },
  });
}

export function useUpdateRole(orgId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ roleKey, version, input }: { roleKey: string; version: number; input: OrgRoleInput }) => {
      const body = { label: input.label, description: input.description ?? null, permissions: input.permissions };
      return (await request<OrgRole>(`/orgs/${orgId}/roles/${roleKey}`, { method: "PUT", body, ifMatch: version })).data;
    },
    meta: { handlesValidation: true, errorTitle: "Couldn't save the role" },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.roles(orgId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.access(orgId) }),
      ]);
    },
  });
}

export function useDeleteRole(orgId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ roleKey, version }: { roleKey: string; version: number }) =>
      api.delete<void>(`/orgs/${orgId}/roles/${roleKey}`, { ifMatch: version, responseType: "none" }),
    meta: { silent: true },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.roles(orgId) });
    },
  });
}
