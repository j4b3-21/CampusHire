"use client";

import * as React from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";

import { appendFilters, parseFilters } from "@/lib/api/record-query";
import type { RecordFilter, RecordQueryParams } from "@/types/records";

export interface TableUrlState {
  /** Zero-based (the URL shows `page=2` for the second page). */
  page: number;
  size: number;
  sort: string;
  q: string;
  filters: RecordFilter[];
  deleted: boolean;
}

export const PAGE_SIZES = [10, 20, 50, 100] as const;
const DEFAULT_SIZE = 20;

/** Table state (page, size, sort, search, filters, trash) kept in the URL so views are shareable. */
export function useTableUrlState() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();

  const state = React.useMemo<TableUrlState>(() => {
    const page = Number.parseInt(searchParams.get("page") ?? "1", 10);
    const size = Number.parseInt(searchParams.get("size") ?? String(DEFAULT_SIZE), 10);
    return {
      page: Number.isFinite(page) && page > 1 ? page - 1 : 0,
      size: (PAGE_SIZES as readonly number[]).includes(size) ? size : DEFAULT_SIZE,
      sort: searchParams.get("sort") ?? "",
      q: searchParams.get("q") ?? "",
      filters: parseFilters(searchParams),
      deleted: searchParams.get("deleted") === "true",
    };
  }, [searchParams]);

  const update = React.useCallback(
    (patch: Partial<TableUrlState>) => {
      const resetsPage = ["size", "sort", "q", "filters", "deleted"].some((key) => key in patch);
      const next: TableUrlState = { ...state, ...(resetsPage ? { page: 0 } : {}), ...patch };
      const params = new URLSearchParams();
      if (next.page > 0) params.set("page", String(next.page + 1));
      if (next.size !== DEFAULT_SIZE) params.set("size", String(next.size));
      if (next.sort) params.set("sort", next.sort);
      if (next.q.trim()) params.set("q", next.q.trim());
      if (next.deleted) params.set("deleted", "true");
      appendFilters(params, next.filters);
      const qs = params.toString();
      router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
    },
    [state, router, pathname]
  );

  const query = React.useMemo<RecordQueryParams>(
    () => ({
      page: state.page,
      size: state.size,
      sort: state.sort || undefined,
      q: state.q || undefined,
      deleted: state.deleted || undefined,
      filters: state.filters.length ? state.filters : undefined,
    }),
    [state]
  );

  return { state, update, query };
}
