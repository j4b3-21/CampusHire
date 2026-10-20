import type { JsonValue } from "@/types/api";
import type { ModulePermission, PermissionDocument, RuleScope, ScopeRule, WhereClause } from "@/types/access";

export const RULE_ACTIONS = ["read", "update", "delete"] as const;
export type RuleAction = (typeof RULE_ACTIONS)[number];

export const SCOPE_LABELS: Record<RuleScope, string> = {
  NONE: "None",
  ALL: "All",
  OWN: "Own",
  ATTRIBUTE: "By attribute",
};

export const SCOPE_HINTS: Record<RuleScope, string> = {
  NONE: "No records",
  ALL: "Every record",
  OWN: "Records they created",
  ATTRIBUTE: "Records whose field matches the member's attribute",
};

/** Field `$id` compares the record ID itself (e.g. a recruiter's own company). */
export const RECORD_ID_FIELD = "$id";

export interface WhereDraft {
  field: string;
  values: string[];
}

export interface RuleDraft {
  scope: RuleScope;
  field: string;
  attribute: string;
  where: WhereDraft[];
}

export interface ModuleDraft {
  read: RuleDraft;
  create: boolean;
  update: RuleDraft;
  delete: RuleDraft;
  hiddenFields: string[];
  readOnlyFields: string[];
}

const NONE_RULE: RuleDraft = { scope: "NONE", field: "", attribute: "", where: [] };

export function emptyModuleDraft(): ModuleDraft {
  return { read: NONE_RULE, create: false, update: NONE_RULE, delete: NONE_RULE, hiddenFields: [], readOnlyFields: [] };
}

function ruleToDraft(rule: ScopeRule | undefined): RuleDraft {
  if (!rule) return NONE_RULE;
  if (typeof rule === "string") return { ...NONE_RULE, scope: rule };
  return {
    scope: rule.scope,
    field: rule.field ?? "",
    attribute: rule.attribute ?? "",
    where: Object.entries(rule.where ?? {}).map(([field, values]) => ({ field, values: [...values] })),
  };
}

function draftToRule(draft: RuleDraft): ScopeRule | undefined {
  if (draft.scope === "NONE") return undefined;
  const where: WhereClause = {};
  for (const clause of draft.where) {
    if (clause.field && clause.values.length) where[clause.field] = clause.values;
  }
  const hasWhere = Object.keys(where).length > 0;
  if (draft.scope !== "ATTRIBUTE" && !hasWhere) return draft.scope;
  return {
    scope: draft.scope,
    ...(draft.scope === "ATTRIBUTE" ? { field: draft.field, attribute: draft.attribute } : {}),
    ...(hasWhere ? { where } : {}),
  };
}

export function toModuleDraft(permission: ModulePermission | undefined): ModuleDraft {
  if (!permission) return emptyModuleDraft();
  return {
    read: ruleToDraft(permission.read),
    create: permission.create === true,
    update: ruleToDraft(permission.update),
    delete: ruleToDraft(permission.delete),
    hiddenFields: [...(permission.hiddenFields ?? [])],
    readOnlyFields: [...(permission.readOnlyFields ?? [])],
  };
}

/** Compact permission entry; `undefined` when the role grants nothing for the module. */
export function fromModuleDraft(draft: ModuleDraft): ModulePermission | undefined {
  const permission: ModulePermission = {};
  const read = draftToRule(draft.read);
  const update = draftToRule(draft.update);
  const remove = draftToRule(draft.delete);
  if (read) permission.read = read;
  if (draft.create) permission.create = true;
  if (update) permission.update = update;
  if (remove) permission.delete = remove;
  if (draft.hiddenFields.length) permission.hiddenFields = draft.hiddenFields;
  if (draft.readOnlyFields.length) permission.readOnlyFields = draft.readOnlyFields;
  return Object.keys(permission).length ? permission : undefined;
}

export function toDocument(drafts: Record<string, ModuleDraft>): PermissionDocument {
  const modules: Record<string, ModulePermission> = {};
  for (const [key, draft] of Object.entries(drafts)) {
    const permission = fromModuleDraft(draft);
    if (permission) modules[key] = permission;
  }
  return { modules };
}

export function fromDocument(document: PermissionDocument | null | undefined): Record<string, ModuleDraft> {
  const drafts: Record<string, ModuleDraft> = {};
  for (const [key, permission] of Object.entries(document?.modules ?? {})) drafts[key] = toModuleDraft(permission);
  return drafts;
}

function isScopeRule(value: JsonValue | undefined): boolean {
  if (value === undefined) return true;
  if (typeof value === "string") return ["NONE", "ALL", "OWN"].includes(value);
  return typeof value === "object" && value !== null && !Array.isArray(value) && typeof value.scope === "string";
}

/** Structural check of a JSON permission document typed by hand (the server validates the details). */
export function parsePermissionJson(text: string): { document: PermissionDocument } | { error: string } {
  let parsed: JsonValue;
  try {
    parsed = JSON.parse(text) as JsonValue;
  } catch (error) {
    return { error: error instanceof Error ? error.message : "Invalid JSON" };
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return { error: "Expected an object like {\"modules\": {…}}" };
  const modules = parsed.modules;
  if (typeof modules !== "object" || modules === null || Array.isArray(modules)) return { error: "\"modules\" must be an object" };
  for (const [key, entry] of Object.entries(modules)) {
    if (typeof entry !== "object" || entry === null || Array.isArray(entry)) return { error: `modules.${key} must be an object` };
    for (const action of RULE_ACTIONS) {
      if (!isScopeRule(entry[action])) return { error: `modules.${key}.${action} must be NONE, ALL, OWN or {scope, …}` };
    }
    if (entry.create !== undefined && typeof entry.create !== "boolean") return { error: `modules.${key}.create must be true or false` };
  }
  return { document: parsed as unknown as PermissionDocument };
}

/** One-line summary of a rule for compact displays. */
export function ruleSummary(draft: RuleDraft) {
  if (draft.scope === "ATTRIBUTE") return `${draft.field || "?"} = member.${draft.attribute || "?"}`;
  return SCOPE_LABELS[draft.scope];
}
