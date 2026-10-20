import type { Instant, Uuid } from "@/types/api";
import type { FieldType } from "@/types/metadata";

export type ExchangeFormat = "xlsx" | "csv";
export type ImportMode = "CREATE" | "UPSERT";
export type ImportStatus = "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";

export interface ImportableField {
  key: string;
  label: string;
  header: string;
  type: FieldType;
  required: boolean;
  unique: boolean;
}

/** `POST .../imports/preview` (nothing is stored). */
export interface ImportPreview {
  fileName: string;
  format: "XLSX" | "CSV" | string;
  headers: string[];
  /** First 20 data rows aligned with `headers`. */
  rows: string[][];
  totalRows: number;
  maxRows: number;
  suggestedMapping: Record<string, string>;
  fields: ImportableField[];
}

/** Header -> field key; `null` skips the column. */
export type ImportMapping = Record<string, string | null>;

export interface ImportOptions {
  mode: ImportMode;
  matchField?: string;
  dryRun: boolean;
  mapping: ImportMapping;
}

export interface ImportRowError {
  row: number;
  column: string | null;
  field: string | null;
  code: string;
  message: string;
}

export interface ImportJob {
  id: Uuid;
  module: string;
  fileName: string;
  fileId: Uuid | null;
  format: string;
  mode: ImportMode;
  matchField: string | null;
  mapping: ImportMapping;
  dryRun: boolean;
  status: ImportStatus;
  totalRows: number | null;
  processedRows: number;
  createdRows: number;
  updatedRows: number;
  failedRows: number;
  errorReportAvailable: boolean;
  errors: ImportRowError[];
  errorMessage: string | null;
  createdBy: Uuid | null;
  createdAt: Instant;
  startedAt: Instant | null;
  finishedAt: Instant | null;
}

export const FINISHED_IMPORT_STATUSES: readonly ImportStatus[] = ["COMPLETED", "FAILED", "CANCELLED"];
