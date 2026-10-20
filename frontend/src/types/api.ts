/** Offset-based page returned by every list endpoint. `page` is zero-based. */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface FieldViolation {
  /** Dotted path, e.g. `data.cgpa` for record values or `name` for top-level properties. */
  field: string;
  code: string;
  message: string;
}

/** RFC 9457 problem details with the BM4A extension members. */
export interface ProblemDetails {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  code?: string;
  errors?: FieldViolation[];
  correlationId?: string;
}

export type Uuid = string;
/** ISO-8601 UTC timestamp. */
export type Instant = string;

export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = { [key: string]: JsonValue };
