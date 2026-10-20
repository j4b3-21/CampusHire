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

export interface Member {
  userId: Uuid;
  email?: string | null;
  displayName?: string | null;
  role: MembershipRole;
  status?: string;
  createdAt?: Instant;
}
