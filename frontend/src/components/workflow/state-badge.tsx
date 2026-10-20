import * as React from "react";

import { Badge } from "@/components/ui/badge";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { CATEGORY_LABELS, stateColor } from "@/lib/workflow";
import { cn } from "@/lib/utils";
import type { WorkflowCategory, WorkflowState } from "@/types/workflow";

type StateLike = Pick<WorkflowState, "color" | "category"> & { label: string };

export function StateDot({ state, className }: { state: Pick<WorkflowState, "color" | "category">; className?: string }) {
  const color = stateColor(state);
  return (
    <span
      aria-hidden
      className={cn("size-2 shrink-0 rounded-full", color.className.dot, className)}
      style={color.style?.dot}
    />
  );
}

/** Workflow state as a colored badge; the tooltip names the category (open, in progress, won, lost). */
export function StateBadge({
  state,
  className,
  showCategory = false,
}: {
  state: StateLike;
  className?: string;
  /** Append the category name inside the badge. */
  showCategory?: boolean;
}) {
  const color = stateColor(state);
  const category = CATEGORY_LABELS[state.category as WorkflowCategory] ?? state.category;
  return (
    <SimpleTooltip label={`${category} stage`}>
      <Badge
        variant="neutral"
        className={cn("max-w-48 gap-1.5", color.className.badge, className)}
        style={color.style?.badge}
      >
        <StateDot state={state} className="size-1.5" />
        <span className="truncate">{state.label}</span>
        {showCategory ? <span className="font-normal opacity-70">· {category}</span> : null}
        <span className="sr-only">({category})</span>
      </Badge>
    </SimpleTooltip>
  );
}

/** Falls back to the raw value when the state is not part of the workflow (e.g. legacy data). */
export function WorkflowValue({ states, value }: { states: WorkflowState[]; value: string }) {
  const state = states.find((s) => s.key === value);
  if (!state) return <Badge variant="outline">{value}</Badge>;
  return <StateBadge state={state} />;
}
