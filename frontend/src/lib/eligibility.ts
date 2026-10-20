import type { JsonObject, JsonValue } from "@/types/api";
import type { EligibilityRules } from "@/types/campushire";

/** Form state: numbers as strings so partial input ("7.") survives re-renders. */
export interface RulesDraft {
  minCgpa: string;
  maxActiveBacklogs: string;
  departments: string[];
  batches: string[];
  programs: string[];
  minTenth: string;
  minTwelfth: string;
  excludePlaced: boolean;
}

export type RulesDraftErrors = Partial<Record<keyof RulesDraft, string>>;

function asObject(value: JsonValue | undefined): JsonObject | null {
  if (typeof value === "string") {
    try {
      return asObject(JSON.parse(value) as JsonValue);
    } catch {
      return null;
    }
  }
  return typeof value === "object" && value !== null && !Array.isArray(value) ? value : null;
}

function num(value: JsonValue | undefined) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim() !== "" && Number.isFinite(Number(value))) return Number(value);
  return undefined;
}

function strings(value: JsonValue | undefined) {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : undefined;
}

/** Reads the drive's `eligibility` value leniently (absent or malformed rules restrict nothing). */
export function parseRules(value: JsonValue | undefined): EligibilityRules {
  const object = asObject(value);
  if (!object) return {};
  const rules: EligibilityRules = {};
  const minCgpa = num(object.minCgpa);
  const maxActiveBacklogs = num(object.maxActiveBacklogs);
  const minTenth = num(object.minTenth);
  const minTwelfth = num(object.minTwelfth);
  if (minCgpa !== undefined) rules.minCgpa = minCgpa;
  if (maxActiveBacklogs !== undefined) rules.maxActiveBacklogs = maxActiveBacklogs;
  if (minTenth !== undefined) rules.minTenth = minTenth;
  if (minTwelfth !== undefined) rules.minTwelfth = minTwelfth;
  const departments = strings(object.departments);
  const batches = strings(object.batches);
  const programs = strings(object.programs);
  if (departments?.length) rules.departments = departments;
  if (batches?.length) rules.batches = batches;
  if (programs?.length) rules.programs = programs;
  if (object.excludePlaced === true) rules.excludePlaced = true;
  return rules;
}

export function draftFromRules(rules: EligibilityRules): RulesDraft {
  const text = (value: number | undefined) => (value === undefined ? "" : String(value));
  return {
    minCgpa: text(rules.minCgpa),
    maxActiveBacklogs: text(rules.maxActiveBacklogs),
    departments: rules.departments ?? [],
    batches: rules.batches ?? [],
    programs: rules.programs ?? [],
    minTenth: text(rules.minTenth),
    minTwelfth: text(rules.minTwelfth),
    excludePlaced: rules.excludePlaced === true,
  };
}

function parseNumber(
  text: string,
  key: keyof RulesDraft,
  errors: RulesDraftErrors,
  { min, max, integer, label }: { min: number; max: number; integer?: boolean; label: string }
) {
  const trimmed = text.trim();
  if (!trimmed) return undefined;
  const value = Number(trimmed);
  if (!Number.isFinite(value)) {
    errors[key] = `${label} must be a number`;
    return undefined;
  }
  if (integer && !Number.isInteger(value)) {
    errors[key] = `${label} must be a whole number`;
    return undefined;
  }
  if (value < min || value > max) {
    errors[key] = `${label} must be between ${min} and ${max}`;
    return undefined;
  }
  return value;
}

/** Validated rules from the form, with per-field errors. */
export function rulesFromDraft(draft: RulesDraft): { rules: EligibilityRules; errors: RulesDraftErrors } {
  const errors: RulesDraftErrors = {};
  const rules: EligibilityRules = {};
  const minCgpa = parseNumber(draft.minCgpa, "minCgpa", errors, { min: 0, max: 10, label: "Minimum CGPA" });
  const backlogs = parseNumber(draft.maxActiveBacklogs, "maxActiveBacklogs", errors, {
    min: 0,
    max: 50,
    integer: true,
    label: "Active backlogs",
  });
  const tenth = parseNumber(draft.minTenth, "minTenth", errors, { min: 0, max: 100, label: "10th %" });
  const twelfth = parseNumber(draft.minTwelfth, "minTwelfth", errors, { min: 0, max: 100, label: "12th %" });
  if (minCgpa !== undefined) rules.minCgpa = minCgpa;
  if (backlogs !== undefined) rules.maxActiveBacklogs = backlogs;
  if (draft.departments.length) rules.departments = [...draft.departments];
  if (draft.batches.length) rules.batches = [...draft.batches];
  if (draft.programs.length) rules.programs = [...draft.programs];
  if (tenth !== undefined) rules.minTenth = tenth;
  if (twelfth !== undefined) rules.minTwelfth = twelfth;
  if (draft.excludePlaced) rules.excludePlaced = true;
  return { rules, errors };
}

/** Order-insensitive comparison key. */
export function rulesKey(rules: EligibilityRules) {
  const sorted = (values?: string[]) => (values ? [...values].sort() : undefined);
  return JSON.stringify([
    rules.minCgpa ?? null,
    rules.maxActiveBacklogs ?? null,
    sorted(rules.departments) ?? null,
    sorted(rules.batches) ?? null,
    sorted(rules.programs) ?? null,
    rules.minTenth ?? null,
    rules.minTwelfth ?? null,
    rules.excludePlaced === true,
  ]);
}

/** Value written to the drive's JSON field; `null` clears it (no restrictions). */
export function rulesToJson(rules: EligibilityRules): JsonObject | null {
  const json: JsonObject = {};
  if (rules.minCgpa !== undefined) json.minCgpa = rules.minCgpa;
  if (rules.maxActiveBacklogs !== undefined) json.maxActiveBacklogs = rules.maxActiveBacklogs;
  if (rules.departments?.length) json.departments = rules.departments;
  if (rules.batches?.length) json.batches = rules.batches;
  if (rules.programs?.length) json.programs = rules.programs;
  if (rules.minTenth !== undefined) json.minTenth = rules.minTenth;
  if (rules.minTwelfth !== undefined) json.minTwelfth = rules.minTwelfth;
  if (rules.excludePlaced) json.excludePlaced = true;
  return Object.keys(json).length ? json : null;
}

/** Maps server paths (`eligibility.minCgpa`, `data.eligibility.minCgpa`) onto form fields. */
export function serverRuleErrors(errors: { field: string; message: string }[]): RulesDraftErrors {
  const result: RulesDraftErrors = {};
  const keys: (keyof RulesDraft)[] = ["minCgpa", "maxActiveBacklogs", "departments", "batches", "programs", "minTenth", "minTwelfth", "excludePlaced"];
  for (const error of errors) {
    const path = error.field.replace(/^data\./, "").replace(/^eligibility\.?/, "");
    const key = keys.find((candidate) => path === candidate || path.startsWith(`${candidate}[`) || path.startsWith(`${candidate}.`));
    if (key && !result[key]) result[key] = error.message;
  }
  return result;
}
