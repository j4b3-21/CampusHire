"use client";

import * as React from "react";
import { toast } from "sonner";
import { CheckCircle2, GitBranch, XCircle } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { StateBadge } from "@/components/workflow/state-badge";
import { TransitionDialog } from "@/components/workflow/transition-dialog";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useBulkTransition } from "@/lib/api";
import { recordTitle } from "@/lib/metadata/fields";
import { humanize } from "@/lib/metadata/format";
import { reachableTargets } from "@/lib/workflow";
import type { ModuleDefinition } from "@/types/metadata";
import type { RecordItem } from "@/types/records";
import type { BulkTransitionResult, Workflow, WorkflowState } from "@/types/workflow";

const MAX_BULK = 500;

/** Bulk "Move to…" for a table selection, with a per-record results summary. */
export function BulkTransitionAction({
  module,
  workflow,
  selected,
  onDone,
}: {
  module: ModuleDefinition;
  workflow: Workflow;
  selected: RecordItem[];
  onDone: () => void;
}) {
  const { orgId, access, isAdmin } = useOrg();
  const bulk = useBulkTransition(orgId, module.apiKey);
  const [target, setTarget] = React.useState<WorkflowState | null>(null);
  const [result, setResult] = React.useState<{ data: BulkTransitionResult; target: WorkflowState; records: RecordItem[] } | null>(null);
  const targets = React.useMemo(() => reachableTargets(workflow, isAdmin, access), [workflow, isAdmin, access]);

  if (targets.length === 0) return null;
  const tooMany = selected.length > MAX_BULK;
  const requireComment = (state: WorkflowState) =>
    workflow.definition.transitions.some((t) => t.to === state.key && t.requireComment);

  const run = async (state: WorkflowState, comment: string) => {
    const records = selected.slice(0, MAX_BULK);
    const data = await bulk.mutateAsync({ recordIds: records.map((r) => r.id), to: state.key, comment });
    setTarget(null);
    if (data.failed === 0) {
      toast.success(`Moved ${data.succeeded} ${data.succeeded === 1 ? module.labelSingular.toLowerCase() : module.labelPlural.toLowerCase()} to ${state.label}`);
      onDone();
    } else {
      setResult({ data, target: state, records });
    }
  };

  const byId = new Map((result?.records ?? []).map((record) => [record.id, record]));
  const failures = result?.data.results.filter((item) => !item.ok) ?? [];

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="xs" disabled={bulk.isPending || tooMany}>
            <GitBranch /> Move to…
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-52">
          <DropdownMenuLabel>Move {selected.length} selected to</DropdownMenuLabel>
          {targets.map((state) => (
            <DropdownMenuItem key={state.key} onSelect={() => setTarget(state)}>
              <StateBadge state={state} />
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>

      {target ? (
        <TransitionDialog
          open
          onOpenChange={(open) => !open && setTarget(null)}
          title={`Move ${selected.length} ${selected.length === 1 ? module.labelSingular.toLowerCase() : module.labelPlural.toLowerCase()}`}
          subject={`${selected.length} selected`}
          note="Records that can't make this move are skipped and listed afterwards."
          target={target}
          requireComment={requireComment(target)}
          pending={bulk.isPending}
          confirmLabel={`Move ${selected.length}`}
          onConfirm={(comment) => run(target, comment)}
        />
      ) : null}

      <Dialog open={Boolean(result)} onOpenChange={(open) => !open && setResult(null)}>
        <DialogContent className="max-w-xl">
          <DialogHeader>
            <DialogTitle>Moved {result?.data.succeeded ?? 0} of {result?.records.length ?? 0}</DialogTitle>
            <DialogDescription className="flex flex-wrap items-center gap-1.5">
              Target stage {result ? <StateBadge state={result.target} /> : null}
            </DialogDescription>
          </DialogHeader>
          <div className="flex gap-3 text-[13px]">
            <span className="inline-flex items-center gap-1.5 text-success-soft-foreground">
              <CheckCircle2 className="size-4" /> {result?.data.succeeded ?? 0} moved
            </span>
            <span className="inline-flex items-center gap-1.5 text-destructive">
              <XCircle className="size-4" /> {result?.data.failed ?? 0} failed
            </span>
          </div>
          <ul className="max-h-80 divide-y divide-border overflow-y-auto rounded-lg border border-border" aria-label="Failed records">
            {failures.map((item) => {
              const record = byId.get(item.recordId);
              return (
                <li key={item.recordId} className="flex flex-col gap-0.5 px-3 py-2 text-[13px]">
                  <span className="font-medium">{record ? recordTitle(module, record) : item.recordId}</span>
                  <span className="text-xs text-muted-foreground">
                    <span className="font-mono">{humanize(item.error?.code ?? "error")}</span>
                    {item.error?.message ? ` · ${item.error.message}` : null}
                  </span>
                </li>
              );
            })}
          </ul>
          <DialogFooter>
            <Button
              onClick={() => {
                setResult(null);
                onDone();
              }}
            >
              Done
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
