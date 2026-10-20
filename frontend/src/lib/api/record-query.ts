import type { FilterOperator, RecordFilter, RecordQueryParams } from "@/types/records";

const FILTER_KEY = /^filter\[([a-z][a-z0-9_]*)\](?:\[([a-z]+)\])?$/;
const OPERATORS: readonly FilterOperator[] = ["eq", "ne", "gt", "gte", "lt", "lte", "contains", "in", "exists"];

export function isFilterOperator(value: string): value is FilterOperator {
  return (OPERATORS as readonly string[]).includes(value);
}

/** `filter[field][op]=value` pairs, as accepted by the record list endpoint. */
export function appendFilters(params: URLSearchParams, filters: RecordFilter[] = []) {
  for (const filter of filters) {
    if (filter.value === "" && filter.op !== "exists") continue;
    params.append(`filter[${filter.field}][${filter.op}]`, filter.value);
  }
  return params;
}

export function parseFilters(params: URLSearchParams): RecordFilter[] {
  const filters: RecordFilter[] = [];
  params.forEach((value, key) => {
    const match = key.match(FILTER_KEY);
    if (!match) return;
    const op = match[2] ?? "eq";
    if (!isFilterOperator(op)) return;
    filters.push({ field: match[1], op, value });
  });
  return filters;
}

/** Query string for `GET /orgs/{orgId}/modules/{key}/records`. */
export function recordQueryString({ page, size, sort, q, deleted, filters }: RecordQueryParams) {
  const params = new URLSearchParams();
  if (page !== undefined && page > 0) params.set("page", String(page));
  if (size !== undefined) params.set("size", String(size));
  if (sort) params.set("sort", sort);
  if (q && q.trim()) params.set("q", q.trim());
  if (deleted) params.set("deleted", "true");
  appendFilters(params, filters);
  return params;
}

export function parseSort(sort: string | null | undefined): { field: string; desc: boolean }[] {
  if (!sort) return [];
  return sort
    .split(",")
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => (part.startsWith("-") ? { field: part.slice(1), desc: true } : { field: part, desc: false }));
}

export function serializeSort(sort: { field: string; desc: boolean }[]) {
  return sort.map((s) => (s.desc ? `-${s.field}` : s.field)).join(",");
}
