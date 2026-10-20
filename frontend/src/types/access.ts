import type { Instant, Uuid } from "@/types/api";

export type ScopeKeyword = "NONE" | "ALL" | "OWN";
export type RuleScope = ScopeKeyword | "ATTRIBUTE";

/** Constant equality filters: field api key -> allowed values. */
export type WhereClause = Record<string, string[]>;

export interface ScopeRuleObject {
  scope: RuleScope;
  /** Filterable field (or `$id`) compared with the member attribute, for `ATTRIBUTE`. */
  field?: string;
  attribute?: string;
  where?: WhereClause;
}

/** `NONE | ALL | OWN` or `{scope, field?, attribute?, where?}` (ADR-004). */
export type ScopeRule = ScopeKeyword | ScopeRuleObject;

export interface ModulePermission {
  read?: ScopeRule;
  create?: boolean;
  update?: ScopeRule;
  delete?: ScopeRule;
  readOnlyFields?: string[];
  hiddenFields?: string[];
}

export interface PermissionDocument {
  modules: Record<string, ModulePermission>;
}

/** `GET /orgs/{orgId}/roles[/{roleKey}]` (ETag = version). */
export interface OrgRole {
  id: Uuid;
  apiKey: string;
  label: string;
  description?: string | null;
  permissions: PermissionDocument;
  version: number;
  createdAt: Instant;
  createdBy?: Uuid | null;
  updatedAt: Instant;
  updatedBy?: Uuid | null;
}

export interface OrgRoleInput {
  apiKey?: string;
  label: string;
  description?: string | null;
  permissions: PermissionDocument;
}
