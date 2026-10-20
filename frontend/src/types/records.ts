import type { Instant, JsonValue, Uuid } from "@/types/api";

/** `{"amount": "12.50", "currency": "INR"}` - amount is a decimal string. */
export interface CurrencyValue {
  amount: string;
  currency: string;
}

/** FILE_REFERENCE / IMAGE_REFERENCE canonical value. */
export interface FileReference {
  fileId: Uuid;
  name?: string;
  contentType?: string;
}

/** Record values keyed by field api key; canonical encodings per ADR-001. */
export type RecordData = Record<string, JsonValue>;

export interface RecordItem {
  id: Uuid;
  module: string;
  version: number;
  data: RecordData;
  ownerUserId?: Uuid | null;
  createdAt: Instant;
  createdBy?: Uuid | null;
  updatedAt: Instant;
  updatedBy?: Uuid | null;
  deletedAt?: Instant | null;
}

export type RecordChangeType = "CREATE" | "UPDATE" | "DELETE" | "RESTORE" | "REVERT" | string;

export interface RecordVersion {
  version: number;
  changeType: RecordChangeType;
  deleted: boolean;
  data: RecordData;
  changedAt: Instant;
  changedBy?: Uuid | null;
}

export interface RelatedRecord {
  linkId: Uuid;
  module: string;
  recordId: Uuid;
  version: number;
  data: RecordData;
}

export interface RecordLink {
  id: Uuid;
  sourceRecordId: Uuid;
  targetRecordId: Uuid;
  createdAt: Instant;
  createdBy?: Uuid | null;
}

export type FilterOperator = "eq" | "ne" | "gt" | "gte" | "lt" | "lte" | "contains" | "in" | "exists";

export interface RecordFilter {
  field: string;
  op: FilterOperator;
  value: string;
}

export interface RecordQueryParams {
  page?: number;
  size?: number;
  /** Comma separated, `-` prefix for descending, e.g. `-cgpa,full_name`. */
  sort?: string;
  q?: string;
  deleted?: boolean;
  filters?: RecordFilter[];
}

export interface UploadedFile {
  id: Uuid;
  name: string;
  contentType: string;
  sizeBytes: number;
}
