"use client";

import * as React from "react";
import Link from "next/link";
import { toast } from "sonner";
import { ChevronDown, ClipboardList } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { ErrorState } from "@/components/common/error-state";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { LookupValue } from "@/components/records/field-value";
import { TransitionTimeline } from "@/components/workflow/record-workflow";
import { StateDot, WorkflowValue } from "@/components/workflow/state-badge";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { ApiError, useRecords, useWithdrawFromDrive, useWorkflow } from "@/lib/api";
import { WITHDRAWABLE_STAGES, stageLabel } from "@/lib/campushire";
import { formatDateTime, formatRelative } from "@/lib/metadata/format";
import { orgPath } from "@/lib/navigation";
import { cn } from "@/lib/utils";
import { boardStates } from "@/lib/workflow";
import type { RecordItem } from "@/types/records";
import type { Workflow } from "@/types/workflow";

function str(record: RecordItem, key: string) {
  const value = record.data[key];
  return typeof value === "string" ? value : null;
}

/** Forward stages as a compact progress rail; the current one highlighted. */
function StageRail({ workflow, stage }: { workflow: Workflow; stage: string }) {
  const forward = boardStates(workflow).filter((state) => state.category !== "LOST");
  const index = forward.findIndex((state) => state.key === stage);
  const lost = index < 0;
  return (
    <ol className="flex items-center gap-1" aria-label="Stages">
      {forward.map((state, i) => {
        const reached = !lost && i <= index;
        return (
          <li key={state.key} className="flex min-w-0 flex-1 flex-col gap-1">
            <span className={cn("h-1 rounded-full", reached ? "bg-primary" : "bg-muted")} aria-hidden />
            <span className={cn("hidden truncate text-[11px] sm:block", i === index ? "font-medium text-foreground" : "text-subtle-foreground")}>{state.label}</span>
            <span className="sr-only">{reached ? (i === index ? "current stage" : "done") : "not reached"}</span>
          </li>
        );
      })}
    </ol>
  );
}

function ApplicationCard({ record, workflow, onWithdraw }: { record: RecordItem; workflow: Workflow | null | undefined; onWithdraw: (record: RecordItem) => void }) {
  const { orgId } = useOrg();
  const [open, setOpen] = React.useState(false);
  const stage = str(record, "stage") ?? "applied";
  const drive = str(record, "drive");
  const company = str(record, "company");
  const round = str(record, "current_round");
  const state = workflow?.definition.states.find((item) => item.key === stage);

  return (
    <li className="rounded-lg border border-border bg-card">
      <div className="flex flex-col gap-3 p-4">
        <div className="flex items-start gap-3">
          {state ? <StateDot state={state} className="mt-2" /> : null}
          <div className="min-w-0 flex-1">
            <div className="truncate text-[15px] font-semibold tracking-[-0.01em]">
              {company ? <LookupValue orgId={orgId} targetModuleKey="company" id={company} link={false} /> : "Company"}
            </div>
            <div className="truncate text-[13px] text-muted-foreground">
              {drive ? <LookupValue orgId={orgId} targetModuleKey="drive" id={drive} link={false} /> : "Drive"}
              {round ? ` · ${round}` : ""}
            </div>
          </div>
          {workflow ? <WorkflowValue states={workflow.definition.states} value={stage} /> : <Badge variant="neutral">{stageLabel(stage)}</Badge>}
        </div>
        {workflow ? <StageRail workflow={workflow} stage={stage} /> : null}
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span className="text-xs text-muted-foreground" title={formatDateTime(record.createdAt)}>
            Applied {formatRelative(record.createdAt)} · updated {formatRelative(record.updatedAt)}
          </span>
          <div className="flex items-center gap-1.5">
            {WITHDRAWABLE_STAGES.includes(stage) && drive ? (
              <Button variant="outline" size="xs" onClick={() => onWithdraw(record)}>
                Withdraw
              </Button>
            ) : null}
            {workflow ? (
              <Button variant="subtle" size="xs" aria-expanded={open} onClick={() => setOpen(!open)}>
                Timeline <ChevronDown className={cn("transition-transform", open && "rotate-180")} />
              </Button>
            ) : null}
          </div>
        </div>
      </div>
      {open && workflow ? (
        <div className="border-t border-border px-4 py-4">
          <TransitionTimeline orgId={orgId} moduleKey="application" recordId={record.id} workflow={workflow} />
        </div>
      ) : null}
    </li>
  );
}

/** The student's own applications with their stage and history. */
export function StudentApplications() {
  const { orgId } = useOrg();
  const applications = useRecords(orgId, "application", { size: 50, sort: "-updated_at" });
  const workflow = useWorkflow(orgId, "application");
  const withdraw = useWithdrawFromDrive(orgId);
  const [pending, setPending] = React.useState<RecordItem | null>(null);
  const items = applications.data?.items ?? [];

  const confirm = async () => {
    const driveId = pending ? str(pending, "drive") : null;
    if (!driveId) return;
    try {
      await withdraw.mutateAsync({ driveId });
      toast.success("Application withdrawn");
      setPending(null);
    } catch (error) {
      toast.error("Couldn't withdraw", { description: error instanceof ApiError ? error.detail ?? error.friendlyMessage : undefined });
    }
  };

  return (
    <>
      <PageHeader title="My applications" description="Every drive you applied to and where you are in the process." />
      <PageBody className="max-w-3xl">
        {applications.isLoading ? (
          <div className="flex flex-col gap-3">
            {Array.from({ length: 3 }, (_, i) => (
              <Skeleton key={i} className="h-36 rounded-lg" />
            ))}
          </div>
        ) : applications.isError ? (
          <ErrorState error={applications.error} onRetry={() => void applications.refetch()} />
        ) : items.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border-strong">
            <EmptyState
              icon={ClipboardList}
              title="No applications yet"
              description="Apply to an open drive and it shows up here with its stage."
              action={
                <Button asChild>
                  <Link href={orgPath(orgId, "/student/drives")}>Browse drives</Link>
                </Button>
              }
            />
          </div>
        ) : (
          <ul className="flex flex-col gap-3" aria-label="Applications">
            {items.map((record) => (
              <ApplicationCard key={record.id} record={record} workflow={workflow.data} onWithdraw={setPending} />
            ))}
          </ul>
        )}
      </PageBody>
      <AlertDialog open={Boolean(pending)} onOpenChange={(open) => !open && !withdraw.isPending && setPending(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Withdraw this application?</AlertDialogTitle>
            <AlertDialogDescription>You&apos;ll leave this drive&apos;s process and can&apos;t apply to it again.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={withdraw.isPending}>Keep application</AlertDialogCancel>
            <Button variant="destructive" disabled={withdraw.isPending} onClick={() => void confirm()}>
              {withdraw.isPending ? <Spinner className="text-current" /> : null}
              Withdraw
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
