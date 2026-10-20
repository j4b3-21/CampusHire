"use client";

import * as React from "react";
import { useDroppable } from "@dnd-kit/core";
import { ChevronLeft, ChevronRight, Inbox } from "lucide-react";

import { BoardCard } from "@/components/board/board-card";
import { StateDot } from "@/components/workflow/state-badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { errorMessage, useBoardColumn } from "@/lib/api";
import { formatInteger } from "@/lib/metadata/format";
import { CATEGORY_LABELS, stateColor } from "@/lib/workflow";
import { cn } from "@/lib/utils";
import type { FieldDefinition, ModuleDefinition } from "@/types/metadata";
import type { RecordFilter } from "@/types/records";
import type { WorkflowState } from "@/types/workflow";

export const columnId = (state: string) => `column:${state}`;

export interface ColumnDragState {
  /** A card is being dragged. */
  active: boolean;
  /** The dragged card may move here. */
  valid: boolean;
  /** The dragged card comes from this column. */
  source: boolean;
}

export interface BoardColumnProps {
  orgId: string;
  module: ModuleDefinition;
  field: string;
  state: WorkflowState;
  params: { q?: string; filters?: RecordFilter[] };
  drag: ColumnDragState;
  collapsed: boolean;
  onToggleCollapsed?: () => void;
  subtitleField: FieldDefinition | null;
  keyFields: FieldDefinition[];
  canDrag: boolean;
  recentlyDragged: () => boolean;
}

export function BoardColumn({
  orgId,
  module,
  field,
  state,
  params,
  drag,
  collapsed,
  onToggleCollapsed,
  subtitleField,
  keyFields,
  canDrag,
  recentlyDragged,
}: BoardColumnProps) {
  const column = useBoardColumn(orgId, module.apiKey, field, state.key, params);
  const { setNodeRef, isOver } = useDroppable({
    id: columnId(state.key),
    data: { state: state.key },
    disabled: drag.active && !drag.valid && !drag.source,
  });
  const color = stateColor(state);
  const total = column.data?.pages[0]?.totalElements;
  const records = column.data?.pages.flatMap((page) => page.items) ?? [];
  const countLabel = total === undefined ? "" : formatInteger(total);
  const target = drag.active && drag.valid && !drag.source;
  const blocked = drag.active && !drag.valid && !drag.source;

  if (collapsed) {
    return (
      <section
        ref={setNodeRef}
        aria-label={`${state.label}, ${countLabel} records (collapsed)`}
        className={cn(
          "flex w-11 shrink-0 flex-col items-center gap-2.5 rounded-xl border border-border bg-surface py-2.5 transition-[background-color,border-color,opacity]",
          target && "border-dashed border-primary/50 bg-primary-soft/40",
          isOver && target && "border-solid border-primary bg-primary-soft",
          blocked && "opacity-40"
        )}
      >
        <SimpleTooltip label={`Show ${state.label}`} side="right">
          <Button variant="ghost" size="icon-xs" aria-label={`Expand ${state.label}`} aria-expanded={false} onClick={onToggleCollapsed}>
            <ChevronRight />
          </Button>
        </SimpleTooltip>
        <StateDot state={state} />
        <span className="text-xs font-medium text-muted-foreground tabular">{countLabel}</span>
        <span className="rotate-180 text-xs font-medium [writing-mode:vertical-rl]">{state.label}</span>
      </section>
    );
  }

  return (
    <section
      ref={setNodeRef}
      aria-labelledby={`${columnId(state.key)}-heading`}
      className={cn(
        "flex max-h-full w-[18rem] shrink-0 flex-col rounded-xl border border-border bg-surface transition-[background-color,border-color,opacity]",
        target && "border-dashed border-primary/50 bg-primary-soft/30",
        isOver && target && "border-solid border-primary bg-primary-soft/60",
        blocked && "opacity-40"
      )}
    >
      <header
        className={cn("flex items-center gap-2 rounded-t-xl border-b border-border px-3 py-2.5", color.className.soft)}
        style={color.style?.soft}
      >
        <StateDot state={state} />
        <h2 id={`${columnId(state.key)}-heading`} className="min-w-0 truncate text-[13px] font-semibold">
          {state.label}
        </h2>
        <span className="text-xs text-muted-foreground tabular" aria-label={`${countLabel} records`}>
          {total === undefined ? <Skeleton className="h-3 w-5" /> : countLabel}
        </span>
        <span className="ml-auto text-[11px] text-subtle-foreground">{CATEGORY_LABELS[state.category]}</span>
        {onToggleCollapsed ? (
          <SimpleTooltip label={`Collapse ${state.label}`}>
            <Button variant="ghost" size="icon-xs" aria-label={`Collapse ${state.label}`} aria-expanded onClick={onToggleCollapsed} className="-mr-1">
              <ChevronLeft />
            </Button>
          </SimpleTooltip>
        ) : null}
      </header>
      <div className="min-h-24 flex-1 overflow-y-auto p-2">
        {column.isLoading ? (
          <div className="flex flex-col gap-2">
            {Array.from({ length: 3 }, (_, i) => (
              <Skeleton key={i} className="h-20 rounded-lg" />
            ))}
          </div>
        ) : column.isError ? (
          <div className="flex flex-col items-center gap-2 px-3 py-6 text-center text-xs text-muted-foreground">
            <p>{errorMessage(column.error)}</p>
            <Button variant="outline" size="xs" onClick={() => void column.refetch()}>
              Try again
            </Button>
          </div>
        ) : records.length === 0 ? (
          <div
            className={cn(
              "flex flex-col items-center gap-1.5 rounded-lg border border-dashed border-border px-3 py-6 text-center text-xs text-subtle-foreground",
              target && "border-primary/40 text-primary"
            )}
          >
            <Inbox className="size-4" />
            {target ? "Drop here" : "Nothing here"}
          </div>
        ) : (
          <ol className="flex flex-col gap-2" aria-label={`${state.label} records`}>
            {records.map((record) => (
              <BoardCard
                key={record.id}
                orgId={orgId}
                module={module}
                record={record}
                state={state.key}
                subtitleField={subtitleField}
                keyFields={keyFields}
                canDrag={canDrag}
                recentlyDragged={recentlyDragged}
              />
            ))}
          </ol>
        )}
        {column.hasNextPage ? (
          <Button
            variant="ghost"
            size="xs"
            className="mt-2 w-full text-muted-foreground"
            disabled={column.isFetchingNextPage}
            onClick={() => void column.fetchNextPage()}
          >
            {column.isFetchingNextPage ? <Spinner className="size-3.5" /> : null}
            Load more ({formatInteger((total ?? 0) - records.length)} left)
          </Button>
        ) : null}
      </div>
    </section>
  );
}
