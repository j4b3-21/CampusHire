import { format, formatDistanceToNowStrict, isValid, parse, parseISO } from "date-fns";

import type { CurrencyValue, FileReference } from "@/types/records";
import type { JsonValue } from "@/types/api";

const LOCALE = "en-IN";

export function isCurrencyValue(value: unknown): value is CurrencyValue {
  return (
    typeof value === "object" &&
    value !== null &&
    "amount" in value &&
    "currency" in value &&
    typeof (value as CurrencyValue).currency === "string"
  );
}

export function isFileReference(value: unknown): value is FileReference {
  return typeof value === "object" && value !== null && typeof (value as FileReference).fileId === "string";
}

const currencyFormatters = new Map<string, Intl.NumberFormat>();

export function formatCurrency(value: CurrencyValue, options: { compact?: boolean } = {}) {
  const amount = Number(value.amount);
  if (!Number.isFinite(amount)) return `${value.currency} ${value.amount}`;
  if (options.compact && value.currency === "INR" && Math.abs(amount) >= 100_000) {
    const lakhs = amount / 100_000;
    return lakhs >= 100 ? `₹${(lakhs / 100).toFixed(2).replace(/\.?0+$/, "")} Cr` : `₹${lakhs.toFixed(2).replace(/\.?0+$/, "")} L`;
  }
  const key = `${value.currency}`;
  let formatter = currencyFormatters.get(key);
  if (!formatter) {
    try {
      formatter = new Intl.NumberFormat(LOCALE, {
        style: "currency",
        currency: value.currency,
        maximumFractionDigits: 2,
        minimumFractionDigits: 0,
      });
    } catch {
      return `${value.currency} ${value.amount}`;
    }
    currencyFormatters.set(key, formatter);
  }
  return formatter.format(amount);
}

const integerFormatter = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 0 });

export function formatInteger(value: number) {
  return integerFormatter.format(value);
}

/** DECIMAL values are strings with exact precision; keep their digits and only add grouping. */
export function formatDecimal(value: string | number) {
  const text = String(value);
  const [whole, fraction] = text.split(".");
  const wholeNumber = Number(whole);
  if (!Number.isSafeInteger(wholeNumber)) return text;
  const grouped = integerFormatter.format(wholeNumber);
  const sign = whole.startsWith("-") && wholeNumber === 0 ? "-" : "";
  return fraction !== undefined ? `${sign}${grouped}.${fraction}` : `${sign}${grouped}`;
}

export function parseLocalDate(value: string) {
  const date = parse(value.slice(0, 10), "yyyy-MM-dd", new Date());
  return isValid(date) ? date : null;
}

export function formatDate(value: string) {
  const date = parseLocalDate(value);
  return date ? format(date, "d MMM yyyy") : value;
}

export function formatDateTime(value: string) {
  const date = parseISO(value);
  return isValid(date) ? format(date, "d MMM yyyy, HH:mm") : value;
}

export function formatRelative(value: string) {
  const date = parseISO(value);
  return isValid(date) ? `${formatDistanceToNowStrict(date)} ago` : value;
}

export function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function shortId(id: string) {
  return id.slice(0, 8);
}

export function humanize(key: string) {
  const text = key.replace(/[_-]+/g, " ").trim();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export function jsonPreview(value: JsonValue, max = 80) {
  const text = JSON.stringify(value);
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}
