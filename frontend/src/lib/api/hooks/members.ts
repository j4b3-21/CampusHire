"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { useAuthStore } from "@/store/auth-store";
import type { Page } from "@/types/api";
import type {
  CreatedInvitation,
  Invitation,
  InvitationInput,
  InvitationPreview,
  Member,
  MemberUpdate,
} from "@/types/org";

function useAuthed() {
  return useAuthStore((state) => state.status === "authenticated");
}

/** One page of members (admin table). */
export function useMemberPage(orgId: string | undefined, page: number, size = 50) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.memberPage(orgId ?? "", page),
    queryFn: ({ signal }) => api.get<Page<Member>>(`/orgs/${orgId}/members`, { query: { page, size }, signal }),
    enabled: authed && Boolean(orgId),
    placeholderData: keepPreviousData,
  });
}

export function useUpdateMember(orgId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, update }: { userId: string; update: MemberUpdate }) =>
      api.patch<Member>(`/orgs/${orgId}/members/${userId}`, update),
    meta: { handlesValidation: true, errorTitle: "Couldn't update the member" },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.members(orgId) });
    },
  });
}

export function useRemoveMember(orgId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) => api.delete<void>(`/orgs/${orgId}/members/${userId}`, { responseType: "none" }),
    meta: { errorTitle: "Couldn't remove the member" },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.members(orgId) });
    },
  });
}

export function useInvitations(orgId: string | undefined, status: string, page = 0) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.invitationList(orgId ?? "", status, page),
    queryFn: ({ signal }) =>
      api.get<Page<Invitation>>(`/orgs/${orgId}/invitations`, {
        query: { status: status === "ALL" ? undefined : status, page, size: 50 },
        signal,
      }),
    enabled: authed && Boolean(orgId),
    placeholderData: keepPreviousData,
  });
}

export type InvitationRequest =
  | { kind: "generic"; input: InvitationInput }
  | { kind: "recruiter"; companyId: string; email: string }
  | { kind: "coordinator"; departmentId: string; email: string };

/** Generic invitations, or the CampusHire recruiter/coordinator presets (which set role and attributes server-side). */
export function useCreateInvitation(orgId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: InvitationRequest) => {
      switch (request.kind) {
        case "recruiter":
          return api.post<CreatedInvitation>(
            `/orgs/${orgId}/campushire/companies/${request.companyId}/recruiters/invite`,
            { email: request.email }
          );
        case "coordinator":
          return api.post<CreatedInvitation>(
            `/orgs/${orgId}/campushire/departments/${request.departmentId}/coordinators/invite`,
            { email: request.email }
          );
        default:
          return api.post<CreatedInvitation>(`/orgs/${orgId}/invitations`, request.input);
      }
    },
    meta: { silent: true },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.invitations(orgId) });
    },
  });
}

export function useRevokeInvitation(orgId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/orgs/${orgId}/invitations/${id}`, { responseType: "none" }),
    meta: { errorTitle: "Couldn't revoke the invitation" },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.invitations(orgId) });
    },
  });
}

export function useInvitationPreview(token: string | undefined) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.invitationPreview(token ?? ""),
    queryFn: ({ signal }) => api.get<InvitationPreview>(`/invitations/${encodeURIComponent(token!)}`, { signal }),
    enabled: authed && Boolean(token),
    retry: false,
    meta: { silent: true },
  });
}

export function useAcceptInvitation(token: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<Member>(`/invitations/${encodeURIComponent(token)}/accept`),
    meta: { silent: true },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.orgs() });
    },
  });
}
