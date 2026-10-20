"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { queryKeys } from "@/lib/api/keys";
import { useAuthStore } from "@/store/auth-store";
import type { Page } from "@/types/api";
import type { Me, Member, MyAccess, Organization } from "@/types/org";

function useAuthed() {
  return useAuthStore((state) => state.status === "authenticated");
}

export function useMe() {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.me(),
    queryFn: ({ signal }) => api.get<Me>("/me", { signal }),
    enabled: authed,
    staleTime: 5 * 60_000,
  });
}

export function useOrganizations() {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.orgs(),
    queryFn: ({ signal }) => api.get<Organization[]>("/orgs", { signal }),
    enabled: authed,
    staleTime: 60_000,
    meta: { silent: true },
  });
}

export function useOrganization(orgId: string | undefined) {
  const orgs = useOrganizations();
  return { ...orgs, data: orgs.data?.find((org) => org.id === orgId) };
}

export interface CreateOrganizationResult {
  organization: Organization;
  /** `false` when the CampusHire setup endpoint is not available yet (404) or failed. */
  setupCompleted: boolean;
}

/**
 * Creates the institute (`POST /orgs`; the caller becomes OWNER) and then installs the CampusHire app pack.
 * A missing setup endpoint (404) is tolerated so onboarding works before the domain backend ships.
 */
export function useCreateOrganization() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ name }: { name: string }): Promise<CreateOrganizationResult> => {
      const organization = await api.post<Organization>("/orgs", { name });
      let setupCompleted = false;
      try {
        await api.post(`/orgs/${organization.id}/campushire/setup`);
        setupCompleted = true;
      } catch (error) {
        if (!(error instanceof ApiError) || (!error.isNotFound && error.status !== 405 && error.status !== 501)) {
          console.warn("CampusHire setup failed", error);
        }
      }
      return { organization, setupCompleted };
    },
    meta: { handlesValidation: true, errorTitle: "Couldn't create the institute" },
    onSuccess: async ({ organization }) => {
      queryClient.setQueryData<Organization[]>(queryKeys.orgs(), (current) =>
        current ? [...current.filter((org) => org.id !== organization.id), organization] : [organization]
      );
      await queryClient.invalidateQueries({ queryKey: queryKeys.orgs() });
    },
  });
}

/** Runs (or re-runs; it is idempotent) the CampusHire app-pack installation. */
export function useCampusHireSetup(orgId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<unknown>(`/orgs/${orgId}/campushire/setup`),
    meta: { errorTitle: "Couldn't install CampusHire" },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.org(orgId) });
    },
  });
}

/**
 * Role, attributes and per-module capabilities (ADR-004). Resolves `null` when the endpoint is not deployed yet
 * (404/501) so callers can fall back to the membership role.
 */
export function useMyAccess(orgId: string | undefined) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.access(orgId ?? ""),
    queryFn: async ({ signal }) => {
      try {
        return await api.get<MyAccess>(`/orgs/${orgId}/me/access`, { signal });
      } catch (error) {
        if (error instanceof ApiError && (error.isNotFound || error.status === 501 || error.status === 405)) {
          return null;
        }
        throw error;
      }
    },
    enabled: authed && Boolean(orgId),
    staleTime: 2 * 60_000,
  });
}

/** Members of the organization keyed by user id (used to label "created by"). */
export function useMembers(orgId: string | undefined) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.members(orgId ?? ""),
    queryFn: ({ signal }) => api.get<Page<Member>>(`/orgs/${orgId}/members`, { query: { size: 100 }, signal }),
    enabled: authed && Boolean(orgId),
    staleTime: 5 * 60_000,
    select: (page) => new Map(page.items.map((member) => [member.userId, member])),
    meta: { silent: true },
  });
}
