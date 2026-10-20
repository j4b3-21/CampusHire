import { forceRefresh, getValidToken } from "@/lib/auth/keycloak";
import { ApiError } from "@/lib/api/errors";
import type { ProblemDetails } from "@/types/api";

export const API_BASE = "/api/v1";

export type QueryValue = string | number | boolean | null | undefined;
export type QueryInit = URLSearchParams | Record<string, QueryValue | QueryValue[]>;

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  query?: QueryInit;
  /** JSON-serialized unless it is `FormData` or a `Blob`. */
  body?: unknown;
  headers?: Record<string, string>;
  /** Version number or entity tag; sent as `If-Match: "<version>"`. */
  ifMatch?: number | string | null;
  contentType?: string;
  responseType?: "json" | "blob" | "none";
  signal?: AbortSignal;
  /** Attach the bearer token (default `true`). */
  auth?: boolean;
}

export interface ApiResult<T> {
  data: T;
  status: number;
  /** Raw `ETag` response header, e.g. `"3"`. */
  etag: string | null;
  headers: Headers;
}

export function toEntityTag(value: number | string) {
  if (typeof value === "number") return `"${value}"`;
  return value.startsWith('"') || value.startsWith("W/") ? value : `"${value}"`;
}

/** Parses `"3"` / `W/"3"` into the numeric version. */
export function versionFromETag(etag: string | null): number | null {
  const match = etag?.match(/^(?:W\/)?"(\d+)"$/);
  return match ? Number(match[1]) : null;
}

export function buildUrl(path: string, query?: QueryInit) {
  const url = path.startsWith("/api/") ? path : `${API_BASE}${path.startsWith("/") ? path : `/${path}`}`;
  if (!query) return url;
  const params = query instanceof URLSearchParams ? query : new URLSearchParams();
  if (!(query instanceof URLSearchParams)) {
    for (const [key, raw] of Object.entries(query)) {
      const values = Array.isArray(raw) ? raw : [raw];
      for (const value of values) {
        if (value === undefined || value === null || value === "") continue;
        params.append(key, String(value));
      }
    }
  }
  const qs = params.toString();
  return qs ? `${url}?${qs}` : url;
}

async function parseProblem(response: Response): Promise<ProblemDetails> {
  const fallback: ProblemDetails = { status: response.status, title: response.statusText };
  const type = response.headers.get("content-type") ?? "";
  if (!type.includes("json")) return fallback;
  try {
    const body = (await response.json()) as Partial<ProblemDetails>;
    return { ...fallback, ...body, status: body.status ?? response.status };
  } catch {
    return fallback;
  }
}

async function send(url: string, options: RequestOptions, token: string | null) {
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json, application/problem+json");
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (options.ifMatch !== undefined && options.ifMatch !== null) headers.set("If-Match", toEntityTag(options.ifMatch));

  let body: BodyInit | undefined;
  if (options.body instanceof FormData || options.body instanceof Blob) {
    body = options.body;
  } else if (options.body !== undefined) {
    headers.set("Content-Type", options.contentType ?? "application/json");
    body = JSON.stringify(options.body);
  }

  try {
    return await fetch(url, { method: options.method ?? "GET", headers, body, signal: options.signal });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw ApiError.network(error);
  }
}

/** Low-level request returning body, status and ETag. Throws {@link ApiError} for non-2xx responses. */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<ApiResult<T>> {
  const url = buildUrl(path, options.query);
  const useAuth = options.auth !== false;
  let response = await send(url, options, useAuth ? await getValidToken() : null);

  if (response.status === 401 && useAuth) {
    const refreshed = await forceRefresh();
    if (refreshed) response = await send(url, options, refreshed);
  }

  if (!response.ok) {
    throw new ApiError(await parseProblem(response));
  }

  const etag = response.headers.get("ETag");
  const responseType = options.responseType ?? "json";
  let data: unknown = undefined;
  if (responseType === "blob") {
    data = await response.blob();
  } else if (responseType === "json" && response.status !== 204) {
    const text = await response.text();
    data = text ? JSON.parse(text) : undefined;
  }
  return { data: data as T, status: response.status, etag, headers: response.headers };
}

export const api = {
  request,
  get: <T>(path: string, options?: Omit<RequestOptions, "method" | "body">) =>
    request<T>(path, { ...options, method: "GET" }).then((r) => r.data),
  post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, "method" | "body">) =>
    request<T>(path, { ...options, method: "POST", body }).then((r) => r.data),
  put: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, "method" | "body">) =>
    request<T>(path, { ...options, method: "PUT", body }).then((r) => r.data),
  patch: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, "method" | "body">) =>
    request<T>(path, { ...options, method: "PATCH", body }).then((r) => r.data),
  delete: <T>(path: string, options?: Omit<RequestOptions, "method">) =>
    request<T>(path, { ...options, method: "DELETE" }).then((r) => r.data),
};
