import { format, isValid, parseISO } from "date-fns";
import { z } from "zod";

import { isCurrencyValue, isFileReference, parseLocalDate } from "@/lib/metadata/format";
import { selectOptions } from "@/lib/metadata/fields";
import type { JsonValue } from "@/types/api";
import type { FieldDefinition } from "@/types/metadata";
import type { FileReference, RecordData } from "@/types/records";

export interface CurrencyFormValue {
  amount: string;
  currency: string;
}

/**
 * Form-side representation of a value. Numbers and decimals are edited as strings (no float rounding), DATETIME as a
 * local `yyyy-MM-ddTHH:mm` string, JSON as text.
 */
export type FormFieldValue = string | boolean | string[] | CurrencyFormValue | FileReference | null;
export type RecordFormValues = Record<string, FormFieldValue>;

export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
export const PHONE_PATTERN = /^\+?[0-9\s\-().]{4,32}$/;
export const INTEGER_PATTERN = /^-?\d+$/;
export const DECIMAL_PATTERN = /^-?\d+(\.\d+)?$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CURRENCY_CODE = /^[A-Z]{3}$/;
const LOCAL_DATETIME = "yyyy-MM-dd'T'HH:mm";

export const DEFAULT_CURRENCIES = ["INR", "USD", "EUR", "GBP"];

export function defaultCurrency(field: FieldDefinition) {
  return field.config?.currencies?.[0] ?? "INR";
}

export function emptyFormValue(field: FieldDefinition): FormFieldValue {
  switch (field.type) {
    case "BOOLEAN":
      return false;
    case "MULTI_SELECT":
      return [];
    case "CURRENCY":
      return { amount: "", currency: defaultCurrency(field) };
    case "FILE_REFERENCE":
    case "IMAGE_REFERENCE":
      return null;
    default:
      return "";
  }
}

/** Canonical API value -> form value. */
export function toFormValue(field: FieldDefinition, value: JsonValue | undefined): FormFieldValue {
  if (value === undefined || value === null) return emptyFormValue(field);
  switch (field.type) {
    case "INTEGER":
    case "DECIMAL":
      return typeof value === "number" || typeof value === "string" ? String(value) : "";
    case "BOOLEAN":
      return value === true;
    case "DATETIME": {
      if (typeof value !== "string") return "";
      const date = parseISO(value);
      return isValid(date) ? format(date, LOCAL_DATETIME) : "";
    }
    case "CURRENCY":
      return isCurrencyValue(value)
        ? { amount: String(value.amount), currency: value.currency }
        : emptyFormValue(field);
    case "MULTI_SELECT":
      return Array.isArray(value) ? value.filter((v): v is string => typeof v === "string") : [];
    case "JSON":
      return JSON.stringify(value, null, 2);
    case "FILE_REFERENCE":
    case "IMAGE_REFERENCE":
      return isFileReference(value) ? value : null;
    default:
      return typeof value === "string" ? value : String(value);
  }
}

export function initialFormValues(fields: FieldDefinition[], data?: RecordData): RecordFormValues {
  const values: RecordFormValues = {};
  for (const field of fields) {
    const source = data ? data[field.apiKey] : (field.defaultValue ?? undefined);
    values[field.apiKey] = toFormValue(field, source);
  }
  return values;
}

/** Form value -> canonical API value; `null` means "no value". */
export function toApiValue(field: FieldDefinition, value: FormFieldValue): JsonValue {
  switch (field.type) {
    case "BOOLEAN":
      return value === true;
    case "INTEGER": {
      const text = typeof value === "string" ? value.trim() : "";
      return text ? Number.parseInt(text, 10) : null;
    }
    case "DECIMAL": {
      const text = typeof value === "string" ? value.trim() : "";
      return text || null;
    }
    case "DATETIME": {
      const text = typeof value === "string" ? value : "";
      if (!text) return null;
      const date = new Date(text);
      return Number.isNaN(date.getTime()) ? null : date.toISOString().replace(/\.000Z$/, "Z");
    }
    case "CURRENCY": {
      if (!value || typeof value !== "object" || Array.isArray(value) || !("amount" in value)) return null;
      const amount = value.amount.trim();
      return amount ? { amount, currency: value.currency } : null;
    }
    case "MULTI_SELECT":
      return Array.isArray(value) && value.length > 0 ? value : null;
    case "JSON": {
      const text = typeof value === "string" ? value.trim() : "";
      return text ? (JSON.parse(text) as JsonValue) : null;
    }
    case "FILE_REFERENCE":
    case "IMAGE_REFERENCE": {
      if (!value || typeof value !== "object" || !("fileId" in value)) return null;
      const ref: Record<string, JsonValue> = { fileId: value.fileId };
      if (value.name) ref.name = value.name;
      if (value.contentType) ref.contentType = value.contentType;
      return ref;
    }
    default: {
      const text = typeof value === "string" ? value.trim() : "";
      return text || null;
    }
  }
}

function asNumber(value: number | string | undefined) {
  if (value === undefined) return undefined;
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}

function rangeIssue(n: number, field: FieldDefinition): string | null {
  const min = asNumber(field.config?.min);
  const max = asNumber(field.config?.max);
  if (min !== undefined && n < min) return `Must be at least ${min}`;
  if (max !== undefined && n > max) return `Must be at most ${max}`;
  return null;
}

function textIssue(text: string, field: FieldDefinition): string | null {
  const defaultMax = field.type === "LONG_TEXT" ? 10_000 : field.type === "EMAIL" ? 254 : field.type === "URL" ? 2048 : 255;
  const max = field.config?.maxLength ?? defaultMax;
  const min = field.config?.minLength;
  if (min !== undefined && text.length < min) return `Use at least ${min} characters`;
  if (text.length > max) return `Use at most ${max} characters`;
  if (field.type === "EMAIL" && !EMAIL_PATTERN.test(text)) return "Enter a valid email address";
  if (field.type === "PHONE" && !PHONE_PATTERN.test(text)) return "Use digits, spaces, + - ( ) . (4 to 32 characters)";
  if (field.type === "URL") {
    try {
      const url = new URL(text);
      if (url.protocol !== "http:" && url.protocol !== "https:") return "Use an http:// or https:// link";
    } catch {
      return "Enter a full link, starting with https://";
    }
  }
  return null;
}

function decimalIssue(text: string, field: FieldDefinition): string | null {
  if (!DECIMAL_PATTERN.test(text)) return "Enter a number, e.g. 8.25";
  const scale = field.config?.scale ?? 6;
  const fraction = text.split(".")[1] ?? "";
  if (fraction.length > scale) return scale === 0 ? "Whole numbers only" : `Use at most ${scale} decimal places`;
  return rangeIssue(Number(text), field);
}

/** Validation message for one field value, or `null`. Mirrors the server rules in ADR-001. */
export function validateFieldValue(field: FieldDefinition, value: FormFieldValue): string | null {
  const required = field.required;
  switch (field.type) {
    case "BOOLEAN":
      return null;
    case "INTEGER": {
      const text = typeof value === "string" ? value.trim() : "";
      if (!text) return required ? "Required" : null;
      if (!INTEGER_PATTERN.test(text)) return "Enter a whole number";
      return rangeIssue(Number(text), field);
    }
    case "DECIMAL": {
      const text = typeof value === "string" ? value.trim() : "";
      if (!text) return required ? "Required" : null;
      return decimalIssue(text, field);
    }
    case "CURRENCY": {
      const currency = value && typeof value === "object" && "amount" in value ? value : null;
      const amount = currency?.amount.trim() ?? "";
      if (!amount) return required ? "Required" : null;
      if (!currency || !CURRENCY_CODE.test(currency.currency)) return "Pick a currency";
      const allowed = field.config?.currencies;
      if (allowed?.length && !allowed.includes(currency.currency)) return `Use one of ${allowed.join(", ")}`;
      return decimalIssue(amount, { ...field, config: { ...field.config, scale: field.config?.scale ?? 2 } });
    }
    case "DATE": {
      const text = typeof value === "string" ? value : "";
      if (!text) return required ? "Required" : null;
      return parseLocalDate(text) ? null : "Enter a valid date";
    }
    case "DATETIME": {
      const text = typeof value === "string" ? value : "";
      if (!text) return required ? "Required" : null;
      return Number.isNaN(new Date(text).getTime()) ? "Enter a valid date and time" : null;
    }
    case "SINGLE_SELECT": {
      const text = typeof value === "string" ? value : "";
      if (!text) return required ? "Required" : null;
      return selectOptions(field).some((option) => option.value === text) ? null : "Pick one of the options";
    }
    case "MULTI_SELECT": {
      const values = Array.isArray(value) ? value : [];
      if (values.length === 0) return required ? "Pick at least one" : null;
      const max = field.config?.maxSelections;
      if (max !== undefined && values.length > max) return `Pick at most ${max}`;
      return null;
    }
    case "JSON": {
      const text = typeof value === "string" ? value.trim() : "";
      if (!text) return required ? "Required" : null;
      try {
        JSON.parse(text);
      } catch {
        return "Not valid JSON";
      }
      const maxBytes = field.config?.maxBytes ?? 16_384;
      return new TextEncoder().encode(text).length > maxBytes ? `Keep it under ${maxBytes} bytes` : null;
    }
    case "FILE_REFERENCE":
    case "IMAGE_REFERENCE": {
      const ref = value && typeof value === "object" && "fileId" in value ? value : null;
      if (!ref) return required ? "Required" : null;
      if (field.type === "IMAGE_REFERENCE" && ref.contentType && !ref.contentType.startsWith("image/")) {
        return "Upload an image";
      }
      return null;
    }
    case "LOOKUP": {
      const text = typeof value === "string" ? value : "";
      if (!text) return required ? "Required" : null;
      return UUID_PATTERN.test(text) ? null : "Pick a record";
    }
    default: {
      const text = typeof value === "string" ? value.trim() : "";
      if (!text) return required ? "Required" : null;
      return textIssue(text, field);
    }
  }
}

/** Zod schema over {@link RecordFormValues}; only editable fields are validated. */
export function buildRecordSchema(fields: FieldDefinition[]) {
  const shape: Record<string, z.ZodType<FormFieldValue>> = {};
  for (const field of fields) {
    shape[field.apiKey] = z.custom<FormFieldValue>().superRefine((value, ctx) => {
      const message = validateFieldValue(field, value);
      if (message) ctx.addIssue({ code: "custom", message });
    });
  }
  return z.looseObject(shape) as unknown as z.ZodType<RecordFormValues, RecordFormValues>;
}

/** Create payload: every editable field with a value. */
export function createPayload(fields: FieldDefinition[], values: RecordFormValues): RecordData {
  const data: RecordData = {};
  for (const field of fields) {
    const value = toApiValue(field, values[field.apiKey] ?? emptyFormValue(field));
    if (value !== null) data[field.apiKey] = value;
  }
  return data;
}

/** JSON Merge Patch payload: only changed fields; `null` clears a value. */
export function patchPayload(
  fields: FieldDefinition[],
  values: RecordFormValues,
  initial: RecordFormValues
): RecordData {
  const data: RecordData = {};
  for (const field of fields) {
    const next = toApiValue(field, values[field.apiKey] ?? emptyFormValue(field));
    const before = toApiValue(field, initial[field.apiKey] ?? emptyFormValue(field));
    if (JSON.stringify(next) !== JSON.stringify(before)) data[field.apiKey] = next;
  }
  return data;
}
