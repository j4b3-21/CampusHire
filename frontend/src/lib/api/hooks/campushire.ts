"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api, request } from "@/lib/api/client";
import { downloadBlob } from "@/lib/api/download";
import { ApiError } from "@/lib/api/errors";
import { queryKeys } from "@/lib/api/keys";
import { useAuthStore } from "@/store/auth-store";
import type { JsonValue, Page } from "@/types/api";
import type {
  Applicant,
  ApplicantQuery,
  ApplicationView,
  CampusDashboard,
  CampusHireMe,
  ClaimResult,
  DriveAction,
  DriveState,
  EligibilityPreview,
  OfferView,
  OnboardingStatus,
  PipelineResult,
  StudentDrive,
  StudentDriveFilter,
} from "@/types/campushire";

function useAuthed() {
  return useAuthStore((state) => state.status === "authenticated");
}

const campus = (orgId: string) => `/orgs/${orgId}/campushire`;

/** Persona, linked student and capabilities (`GET .../campushire/me`); `null` when the pack is not deployed. */
export function useCampusHireMe(orgId: string | undefined, options: { enabled?: boolean } = {}) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.campusMe(orgId ?? ""),
    queryFn: async ({ signal }) => {
      try {
        return await api.get<CampusHireMe>(`${campus(orgId!)}/me`, { signal });
      } catch (error) {
        if (error instanceof ApiError && (error.isNotFound || error.status === 405 || error.status === 501)) return null;
        throw error;
      }
    },
    enabled: authed && Boolean(orgId) && options.enabled !== false,
    staleTime: 2 * 60_000,
    meta: { silent: true },
  });
}

export function useCampusDashboard(orgId: string | undefined, options: { enabled?: boolean } = {}) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.dashboard(orgId ?? ""),
    queryFn: ({ signal }) => api.get<CampusDashboard>(`${campus(orgId!)}/dashboard`, { signal }),
    enabled: authed && Boolean(orgId) && options.enabled !== false,
    staleTime: 30_000,
    meta: { silent: true },
  });
}

// ---- Onboarding ----

export function useCampusOnboarding(options: { enabled?: boolean } = {}) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.onboarding(),
    queryFn: async ({ signal }): Promise<OnboardingStatus | null> => {
      try {
        return await api.get<OnboardingStatus>("/campushire/onboarding", { signal });
      } catch (error) {
        if (error instanceof ApiError && (error.isNotFound || error.status === 405 || error.status === 501)) return null;
        throw error;
      }
    },
    enabled: authed && options.enabled !== false,
    staleTime: 60_000,
    retry: false,
    meta: { silent: true },
  });
}

export function useClaimStudentRecord() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (organizationId: string) => api.post<ClaimResult>("/campushire/onboarding/claim", { organizationId }),
    meta: { silent: true },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.orgs() }),
        queryClient.invalidateQueries({ queryKey: queryKeys.onboarding() }),
      ]);
    },
  });
}

// ---- Drives (staff) ----

export function useEligibilityPreview(
  orgId: string,
  driveId: string,
  /** Drive record version: a new version (saved rules) means a new preview. */
  version: number,
  options: {
    enabled?: boolean;
    sample?: number;
    /** Unsaved eligibility JSON to evaluate instead of the drive's saved rules. */
    draft?: JsonValue | null;
  } = {}
) {
  const authed = useAuthed();
  const draft = options.draft ?? null;
  const draftKey = draft === null ? null : JSON.stringify(draft);
  const path = `${campus(orgId)}/drives/${driveId}/eligibility/preview`;
  const query = { sample: options.sample ?? 10 };
  return useQuery({
    queryKey: queryKeys.eligibilityPreview(orgId, driveId, version, draftKey),
    queryFn: ({ signal }) =>
      draft === null
        ? api.get<EligibilityPreview>(path, { query, signal })
        : api.post<EligibilityPreview>(path, { eligibility: draft }, { query, signal }),
    enabled: authed && options.enabled !== false,
    placeholderData: keepPreviousData,
    staleTime: 15_000,
    retry: false,
    meta: { silent: true },
  });
}

export function useDriveLifecycle(orgId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ driveId, action, ifMatch }: { driveId: string; action: DriveAction; ifMatch?: number }) =>
      (await request<DriveState>(`${campus(orgId)}/drives/${driveId}/${action}`, { method: "POST", ifMatch })).data,
    meta: { silent: true },
    onSuccess: async (state) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.record(orgId, "drive", state.id) }),
        queryClient.invalidateQueries({ queryKey: [...queryKeys.records(orgId, "drive"), "list"] }),
        queryClient.invalidateQueries({ queryKey: [...queryKeys.records(orgId, "drive"), "versions", state.id] }),
        queryClient.invalidateQueries({ queryKey: queryKeys.dashboard(orgId) }),
      ]);
    },
  });
}

// ---- Students ----

export function useStudentDrives(orgId: string | undefined, filter: StudentDriveFilter, page = 0, size = 20) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.studentDrives(orgId ?? "", filter, page),
    queryFn: ({ signal }) =>
      api.get<Page<StudentDrive>>(`${campus(orgId!)}/student/drives`, { query: { filter, page, size }, signal }),
    enabled: authed && Boolean(orgId),
    placeholderData: keepPreviousData,
    staleTime: 15_000,
    meta: { silent: true },
  });
}

function useStudentCache(orgId: string) {
  const queryClient = useQueryClient();
  return {
    setApplication(driveId: string, application: ApplicationView) {
      queryClient.setQueriesData<Page<StudentDrive>>(
        { queryKey: [...queryKeys.campus(orgId), "student-drives"] },
        (page) =>
          page
            ? {
                ...page,
                items: page.items.map((drive) =>
                  drive.id === driveId ? { ...drive, application: { id: application.id, stage: application.stage } } : drive
                ),
              }
            : page
      );
    },
    async refresh() {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: [...queryKeys.campus(orgId), "student-drives"] }),
        queryClient.invalidateQueries({ queryKey: queryKeys.dashboard(orgId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.records(orgId, "application") }),
      ]);
    },
  };
}

export function useApplyToDrive(orgId: string) {
  const cache = useStudentCache(orgId);
  return useMutation({
    mutationFn: (driveId: string) => api.post<ApplicationView>(`${campus(orgId)}/drives/${driveId}/apply`),
    meta: { silent: true },
    onSuccess: async (application, driveId) => {
      cache.setApplication(driveId, application);
      await cache.refresh();
    },
  });
}

export function useWithdrawFromDrive(orgId: string) {
  const cache = useStudentCache(orgId);
  return useMutation({
    mutationFn: ({ driveId, comment }: { driveId: string; comment?: string }) =>
      api.post<ApplicationView>(`${campus(orgId)}/drives/${driveId}/withdraw`, comment?.trim() ? { comment: comment.trim() } : undefined),
    meta: { silent: true },
    onSuccess: async (application, { driveId }) => {
      cache.setApplication(driveId, application);
      await cache.refresh();
    },
  });
}

export interface CreateOfferInput {
  applicationId: string;
  /** Rupees per annum. */
  ctc: number;
  offerType: "full_time" | "internship" | "ppo";
  joiningDate?: string;
  respondBy?: string;
  offerLetterFileId?: string;
  override?: boolean;
}

export function useCreateOffer(orgId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateOfferInput) => api.post<OfferView>(`${campus(orgId)}/offers`, input),
    meta: { silent: true },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.records(orgId, "offer") }),
        queryClient.invalidateQueries({ queryKey: queryKeys.records(orgId, "application") }),
        queryClient.invalidateQueries({ queryKey: queryKeys.records(orgId, "student") }),
        queryClient.invalidateQueries({ queryKey: queryKeys.dashboard(orgId) }),
      ]);
    },
  });
}

export function useRespondToOffer(orgId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ offerId, decision }: { offerId: string; decision: "accept" | "decline" }) =>
      api.post<OfferView>(`${campus(orgId)}/offers/${offerId}/${decision}`),
    meta: { silent: true },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.records(orgId, "offer") }),
        queryClient.invalidateQueries({ queryKey: queryKeys.dashboard(orgId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.campusMe(orgId) }),
      ]);
    },
  });
}

// ---- Applicants ----

export function useApplicants(orgId: string, driveId: string, params: ApplicantQuery, options: { enabled?: boolean } = {}) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.applicantList(orgId, driveId, params),
    queryFn: ({ signal }) =>
      api.get<Page<Applicant>>(`${campus(orgId)}/drives/${driveId}/applicants`, {
        query: { stage: params.stage, q: params.q, sort: params.sort, page: params.page, size: params.size },
        signal,
      }),
    enabled: authed && Boolean(driveId) && options.enabled !== false,
    placeholderData: keepPreviousData,
    staleTime: 10_000,
  });
}

export interface ApplicantTransitionInput {
  applicationIds?: string[];
  rollNumbers?: string[];
  to: string;
  comment?: string;
}

function useApplicantCache(orgId: string, driveId: string) {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.applicants(orgId, driveId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.records(orgId, "application") }),
      queryClient.invalidateQueries({ queryKey: queryKeys.dashboard(orgId) }),
    ]);
}

export function useApplicantTransition(orgId: string, driveId: string) {
  const refresh = useApplicantCache(orgId, driveId);
  return useMutation({
    mutationFn: ({ applicationIds, rollNumbers, to, comment }: ApplicantTransitionInput) =>
      api.post<PipelineResult>(`${campus(orgId)}/drives/${driveId}/applicants/transition`, {
        applicationIds: applicationIds?.length ? applicationIds : undefined,
        rollNumbers: rollNumbers?.length ? rollNumbers : undefined,
        to,
        comment: comment?.trim() ? comment.trim() : undefined,
      }),
    meta: { errorTitle: "Couldn't move the applicants" },
    onSuccess: () => refresh(),
  });
}

export function useShortlistUpload(orgId: string, driveId: string) {
  const refresh = useApplicantCache(orgId, driveId);
  return useMutation({
    mutationFn: ({ file, to, comment }: { file: File; to: string; comment?: string }) => {
      const form = new FormData();
      form.append("file", file);
      form.append("to", to);
      if (comment?.trim()) form.append("comment", comment.trim());
      return api.post<PipelineResult>(`${campus(orgId)}/drives/${driveId}/applicants/transition/upload`, form);
    },
    meta: { silent: true },
    onSuccess: () => refresh(),
  });
}

export function downloadApplicantResume(orgId: string, driveId: string, applicant: Applicant) {
  return downloadBlob(`${campus(orgId)}/drives/${driveId}/applicants/${applicant.applicationId}/resume`, {
    fallbackName: applicant.student.resume?.name ?? `${applicant.student.rollNumber}-resume.pdf`,
  });
}
