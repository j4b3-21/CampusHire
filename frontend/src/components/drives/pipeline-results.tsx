"use client";

import * as React from "react";
import { CheckCircle2, HelpCircle, XCircle } from "lucide-react";

import { StateBadge } from "@/components/workflow/state-badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { humanize } from "@/lib/metadata/format";
import type { Applicant, PipelineResult } from "@/types/campushire";
import type { WorkflowState } from "@/types/workflow";

/** Outcome of a bulk move or a shortlist upload: moved, failed (with reasons) and unknown roll numbers. */
export function PipelineResultDialog({
  result,
  target,
  applicants,
  onClose,
}: {
  result: PipelineResult | null;
  target: WorkflowState | undefined;
  /** Rows of the current page, to name failed applications. */
  applicants: Applicant[];
  onClose: () => void;
}) {
  const byId = new Map(applicants.map((applicant) => [applicant.applicationId, applicant]));
  const failures = result?.results.filter((item) => !item.ok) ?? [];
  const unknown = result?.unknownRollNumbers ?? [];
  const total = (result?.succeeded ?? 0) + (result?.failed ?? 0);

  return (
    <Dialog open={Boolean(result)} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>
            Moved {result?.succeeded ?? 0} of {total} {total === 1 ? "applicant" : "applicants"}
          </DialogTitle>
          <DialogDescription className="flex flex-wrap items-center gap-1.5">Target stage {target ? <StateBadge state={target} /> : null}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-wrap gap-x-4 gap-y-1 text-[13px]">
          <span className="inline-flex items-center gap-1.5 text-success-soft-foreground">
            <CheckCircle2 className="size-4" /> {result?.succeeded ?? 0} moved
          </span>
          <span className="inline-flex items-center gap-1.5 text-destructive">
            <XCircle className="size-4" /> {result?.failed ?? 0} failed
          </span>
          {unknown.length > 0 ? (
            <span className="inline-flex items-center gap-1.5 text-warning-soft-foreground">
              <HelpCircle className="size-4" /> {unknown.length} unknown roll {unknown.length === 1 ? "number" : "numbers"}
            </span>
          ) : null}
        </div>
        {failures.length > 0 ? (
          <ul className="max-h-64 divide-y divide-border overflow-y-auto rounded-lg border border-border" aria-label="Failed applicants">
            {failures.map((item, index) => {
              const applicant = item.applicationId ? byId.get(item.applicationId) : undefined;
              const name = applicant ? `${applicant.student.rollNumber} · ${applicant.student.fullName}` : item.rollNumber ?? item.applicationId ?? "Applicant";
              return (
                <li key={`${item.applicationId ?? item.rollNumber}-${index}`} className="flex flex-col gap-0.5 px-3 py-2 text-[13px]">
                  <span className="font-medium">{name}</span>
                  <span className="text-xs text-muted-foreground">
                    <span className="font-mono">{humanize(item.error?.code ?? "error")}</span>
                    {item.error?.message ? ` · ${item.error.message}` : null}
                  </span>
                </li>
              );
            })}
          </ul>
        ) : null}
        {unknown.length > 0 ? (
          <div className="flex flex-col gap-1.5">
            <p className="text-xs font-medium text-muted-foreground">Not applicants of this drive (or not visible to you)</p>
            <p className="max-h-28 overflow-y-auto rounded-lg border border-border bg-surface px-3 py-2 font-mono text-xs leading-relaxed">
              {unknown.join(", ")}
            </p>
          </div>
        ) : null}
        <DialogFooter>
          <Button onClick={onClose}>Done</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
