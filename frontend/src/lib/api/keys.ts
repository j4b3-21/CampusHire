import type { RecordQueryParams } from "@/types/records";

export interface AppQueryMeta extends Record<string, unknown> {
  /** Do not show the automatic error toast. */
  silent?: boolean;
}

export interface AppMutationMeta extends Record<string, unknown> {
  silent?: boolean;
  /** The caller maps `validation_failed` field errors itself (forms). */
  handlesValidation?: boolean;
  /** Toast title used for unexpected failures. */
  errorTitle?: string;
}

declare module "@tanstack/react-query" {
  interface Register {
    queryMeta: AppQueryMeta;
    mutationMeta: AppMutationMeta;
  }
}

export const queryKeys = {
  me: () => ["me"] as const,
  orgs: () => ["orgs"] as const,
  org: (orgId: string) => ["orgs", orgId] as const,
  access: (orgId: string) => ["orgs", orgId, "access"] as const,
  members: (orgId: string) => ["orgs", orgId, "members"] as const,
  modules: (orgId: string) => ["orgs", orgId, "modules"] as const,
  module: (orgId: string, moduleKey: string) => ["orgs", orgId, "modules", moduleKey] as const,
  relationships: (orgId: string) => ["orgs", orgId, "relationships"] as const,
  records: (orgId: string, moduleKey: string) => ["orgs", orgId, "records", moduleKey] as const,
  recordList: (orgId: string, moduleKey: string, params: RecordQueryParams) =>
    ["orgs", orgId, "records", moduleKey, "list", params] as const,
  record: (orgId: string, moduleKey: string, id: string) =>
    ["orgs", orgId, "records", moduleKey, "detail", id] as const,
  versions: (orgId: string, moduleKey: string, id: string, page: number) =>
    ["orgs", orgId, "records", moduleKey, "versions", id, page] as const,
  related: (orgId: string, moduleKey: string, id: string, relationship: string, direction: string, page: number) =>
    ["orgs", orgId, "records", moduleKey, "related", id, relationship, direction, page] as const,
  file: (orgId: string, fileId: string) => ["orgs", orgId, "files", fileId] as const,
  orgDetail: (orgId: string) => ["orgs", orgId, "detail"] as const,
  memberPage: (orgId: string, page: number) => ["orgs", orgId, "members", "page", page] as const,
  invitations: (orgId: string) => ["orgs", orgId, "invitations"] as const,
  invitationList: (orgId: string, status: string, page: number) => ["orgs", orgId, "invitations", status, page] as const,
  invitationPreview: (token: string) => ["invitations", token] as const,
  roles: (orgId: string) => ["orgs", orgId, "roles"] as const,
  role: (orgId: string, roleKey: string) => ["orgs", orgId, "roles", roleKey] as const,
  setup: (orgId: string) => ["orgs", orgId, "campushire-setup"] as const,
  allModules: (orgId: string) => ["orgs", orgId, "modules-all"] as const,
  moduleVersions: (orgId: string, moduleKey: string, page: number) =>
    ["orgs", orgId, "module-versions", moduleKey, page] as const,
  workflow: (orgId: string, moduleKey: string) => ["orgs", orgId, "workflow", moduleKey] as const,
  available: (orgId: string, moduleKey: string, id: string) =>
    ["orgs", orgId, "records", moduleKey, "available", id] as const,
  transitions: (orgId: string, moduleKey: string, id: string) =>
    ["orgs", orgId, "records", moduleKey, "transitions", id] as const,
  boardColumn: (orgId: string, moduleKey: string, state: string, params: RecordQueryParams) =>
    ["orgs", orgId, "records", moduleKey, "list", "board", state, params] as const,
  notifications: () => ["notifications"] as const,
  notificationList: (unreadOnly: boolean) => ["notifications", "list", unreadOnly] as const,
  unreadCount: () => ["notifications", "unread-count"] as const,
  onboarding: () => ["campushire-onboarding"] as const,
  campus: (orgId: string) => ["orgs", orgId, "campushire"] as const,
  campusMe: (orgId: string) => ["orgs", orgId, "campushire", "me"] as const,
  dashboard: (orgId: string) => ["orgs", orgId, "campushire", "dashboard"] as const,
  eligibilityPreview: (orgId: string, driveId: string, version: number) =>
    ["orgs", orgId, "campushire", "eligibility", driveId, version] as const,
  studentDrives: (orgId: string, filter: string, page: number) =>
    ["orgs", orgId, "campushire", "student-drives", filter, page] as const,
  applicants: (orgId: string, driveId: string) => ["orgs", orgId, "campushire", "applicants", driveId] as const,
  applicantList: (orgId: string, driveId: string, params: object) =>
    ["orgs", orgId, "campushire", "applicants", driveId, params] as const,
  analytics: (orgId: string) => ["orgs", orgId, "campushire", "analytics"] as const,
  analyticsPart: (orgId: string, part: string, params: object) =>
    ["orgs", orgId, "campushire", "analytics", part, params] as const,
  imports: (orgId: string, moduleKey: string) => ["orgs", orgId, "imports", moduleKey] as const,
  importJob: (orgId: string, moduleKey: string, jobId: string) => ["orgs", orgId, "imports", moduleKey, "job", jobId] as const,
};
