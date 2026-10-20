import type { Instant, JsonObject, JsonValue, Uuid } from "@/types/api";

export type MembershipRole = "OWNER" | "ADMIN" | "MEMBER";

export interface Organization {
  id: Uuid;
  name: string;
  status: "ACTIVE" | "SUSPENDED" | "ARCHIVED" | string;
  settings?: JsonObject | null;
  role: MembershipRole;
  version: number;
  createdAt: Instant;
  updatedAt: Instant;
}

export interface Me {
  id: Uuid;
  email: string | null;
  displayName: string | null;
  platformAdmin: boolean;
  profile: {
    locale: string | null;
    timeZone: string | null;
    preferences: JsonValue | null;
    updatedAt: Instant | null;
  };
}

export type ReadScope = "NONE" | "ALL" | "OWN" | "ATTRIBUTE" | string;

export interface ModuleCapabilities {
  read: boolean;
  create: boolean;
  update: boolean;
  delete: boolean;
  readScope?: ReadScope;
  hiddenFields: string[];
  readOnlyFields: string[];
}

/** `GET /api/v1/orgs/{orgId}/me/access` (ADR-004). */
export interface MyAccess {
  organizationId: Uuid;
  membershipRole: MembershipRole;
  admin: boolean;
  role: { key: string; label: string } | null;
  attributes: Record<string, JsonValue>;
  modules: Record<string, ModuleCapabilities>;
}

/** Membership attributes: strings or string arrays (record ids such as `{"department": "<uuid>"}`). */
export type MemberAttributes = Record<string, string | string[]>;

export interface Member {
  userId: Uuid;
  email?: string | null;
  displayName?: string | null;
  role: MembershipRole;
  /** Org role key (ADR-004), `null` without one. */
  roleKey?: string | null;
  attributes?: MemberAttributes | null;
  status?: string;
  createdAt?: Instant;
  updatedAt?: Instant;
}

export interface MemberUpdate {
  role?: MembershipRole;
  /** `null` removes the org role; omit to keep it. */
  roleKey?: string | null;
  /** Replaces all attributes. */
  attributes?: MemberAttributes;
}

export const INVITATION_STATUSES = ["PENDING", "ACCEPTED", "EXPIRED", "REVOKED"] as const;
export type InvitationStatus = (typeof INVITATION_STATUSES)[number];

export interface Invitation {
  id: Uuid;
  email: string;
  membershipRole: MembershipRole;
  roleKey?: string | null;
  attributes?: MemberAttributes | null;
  status: InvitationStatus | string;
  expiresAt: Instant;
  createdAt: Instant;
  createdBy?: Uuid | null;
  acceptedAt?: Instant | null;
  acceptedBy?: Uuid | null;
  revokedAt?: Instant | null;
}

/** `POST /orgs/{orgId}/invitations` response; `acceptUrl` is never returned again. */
export interface CreatedInvitation extends Invitation {
  acceptUrl: string;
}

export interface InvitationInput {
  email: string;
  membershipRole: MembershipRole;
  roleKey?: string | null;
  attributes?: MemberAttributes;
}

/** `GET /invitations/{token}`; `email` is masked. */
export interface InvitationPreview {
  organizationId: Uuid;
  organizationName: string;
  membershipRole: MembershipRole;
  roleKey?: string | null;
  roleLabel?: string | null;
  email: string;
  status: InvitationStatus | string;
  expiresAt: Instant;
}

/** `settings.campushire` of the organization. */
export interface CampusHirePolicy {
  maxOffersPerStudent?: number;
  /** Rupees. */
  dreamCtcThreshold?: number;
  allowDreamUpgrade?: boolean;
}

export interface CampusHireSetupInput {
  allowedEmailDomains?: string[];
}

export interface InstalledPack {
  packKey: string;
  version: number;
  label: string;
  definitionSha256?: string;
  installedAt: Instant;
  installedBy?: Uuid | null;
}

/** `GET /orgs/{orgId}/campushire/setup`; extra properties are tolerated. */
export interface CampusHireSetupStatus {
  installed: boolean;
  version?: number | null;
  installedAt?: Instant | null;
  [key: string]: JsonValue | undefined;
}
