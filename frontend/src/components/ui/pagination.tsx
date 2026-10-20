"use client";

import * as React from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

/** Page numbers with ellipses, zero-based `page`. */
export function pageWindow(page: number, totalPages: number, siblings = 1): (number | "gap")[] {
  if (totalPages <= 7) return Array.from({ length: totalPages }, (_, i) => i);
  const first = 0;
  const last = totalPages - 1;
  const start = Math.max(first + 1, page - siblings);
  const end = Math.min(last - 1, page + siblings);
  const items: (number | "gap")[] = [first];
  if (start > first + 1) items.push("gap");
  for (let i = start; i <= end; i++) items.push(i);
  if (end < last - 1) items.push("gap");
  items.push(last);
  return items;
}

export interface PaginationProps {
  page: number;
  totalPages: number;
  onPageChange: (page: number) => void;
  className?: string;
}

export function Pagination({ page, totalPages, onPageChange, className }: PaginationProps) {
  if (totalPages <= 1) return null;
  return (
    <nav aria-label="Pagination" className={cn("flex items-center gap-1", className)}>
      <Button
        variant="ghost"
        size="icon-sm"
        aria-label="Previous page"
        disabled={page <= 0}
        onClick={() => onPageChange(page - 1)}
      >
        <ChevronLeft />
      </Button>
      <div className="hidden items-center gap-0.5 sm:flex">
        {pageWindow(page, totalPages).map((item, index) =>
          item === "gap" ? (
            <span key={`gap-${index}`} className="px-1.5 text-xs text-subtle-foreground">
              …
            </span>
          ) : (
            <Button
              key={item}
              variant={item === page ? "outline" : "ghost"}
              size="icon-sm"
              aria-label={`Page ${item + 1}`}
              aria-current={item === page ? "page" : undefined}
              className={cn("tabular text-[13px]", item !== page && "text-muted-foreground")}
              onClick={() => onPageChange(item)}
            >
              {item + 1}
            </Button>
          )
        )}
      </div>
      <span className="px-2 text-xs text-muted-foreground tabular sm:hidden">
        {page + 1} / {totalPages}
      </span>
      <Button
        variant="ghost"
        size="icon-sm"
        aria-label="Next page"
        disabled={page >= totalPages - 1}
        onClick={() => onPageChange(page + 1)}
      >
        <ChevronRight />
      </Button>
    </nav>
  );
}
