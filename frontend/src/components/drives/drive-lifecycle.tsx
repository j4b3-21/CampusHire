"use client";

import * as React from "react";
import { toast } from "sonner";
import { AlertTriangle, CalendarClock, Megaphone, RotateCw } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { DriveStatusBadge } from "@/components/dashboard/dashboard-kit";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { ApiError, errorMessage, useDriveLifecycle, useRecord, type VersionedRecord } from "@/lib/api";
import { DRIVE_ACTIONS, countdown, driveStatusLabel, type DriveActionInfo } from "@/lib/campushire";
import { formatDateTime } from "@/lib/metadata/format";
import { cn } from "@/lib/utils";
import type { DriveStatus } from "@/types/campushire";

const FIELD_LABELS: Record<string, string> = {
  registration_closes_at: "Registration closes",
  registration_opens_at: "Registration opens",
  job: "Job",
  eligibility: "Eligibility",
};

const CODE_HINTS: Record<string, string> = {
  required: "is required",
  past: "must be in the future",
  order: "must be before the closing time",
};

/** Server problems as readable lines: `errors[]` for validation, otherwise the friendly message. */
function problemLines(error: unknown): string[] {
  if (!(error instanceof ApiError)) return [errorMessage(error)];
  if (error.code === "drive_status_conflict") return ["The drive's status changed in the meantime. Reload to see where it is now."];
  if (error.isVersionConflict) return ["Someone else changed this drive in the meantime. Reload and try again."];
  if (error.errors.length === 0) return [error.friendlyMessage];
  return error.errors.map((violation) => {
    const root = violation.field.split(".")[0].replace(/^data\./, "");
    const label = FIELD_LABELS[root] ?? FIELD_LABELS[violation.field] ?? violation.field;
    if (violation.field.startsWith("eligibility.")) return `Eligibility (${violation.field.slice(12)}): ${violation.message}`;
    return `${label} ${CODE_HINTS[violation.code] ?? violation.message}`;
  });
}

function stringValue(record: VersionedRecord, key: string) {
  const value = record.data[key];
  return typeof value === "string" ? value : null;
}

/** Status, registration window and lifecycle actions (publish/close/start/complete/cancel) of a drive. */
export function DriveLifecyclePanel({ record, canManage }: { record: VersionedRecord; canManage: boolean }) {
  const { orgId } = useOrg();
  const lifecycle = useDriveLifecycle(orgId);
  const reload = useRecord(orgId, "drive", record.id, { silent: true });
  const [pending, setPending] = React.useState<DriveActionInfo | null>(null);
  const [problem, setProblem] = React.useState<{ action: DriveActionInfo; lines: string[]; reloadable: boolean } | null>(null);
  const status = (stringValue(record, "drive_status") ?? "draft") as DriveStatus;
  const opensAt = stringValue(record, "registration_opens_at");
  const closesAt = stringValue(record, "registration_closes_at");
  const closes = status === "published" ? countdown(closesAt) : null;
  const actions = DRIVE_ACTIONS.filter((item) => item.from.includes(status));
  const primary = actions.filter((item) => !item.destructive);
  const destructive = actions.filter((item) => item.destructive);

  const run = async (info: DriveActionInfo) => {
    setProblem(null);
    try {
      const state = await lifecycle.mutateAsync({ driveId: record.id, action: info.action, ifMatch: record.version });
      setPending(null);
      const notified = info.action === "publish" && typeof state.notifiedStudents === "number" ? state.notifiedStudents : null;
      toast.success(`Drive ${driveStatusLabel(state.driveStatus).toLowerCase()}`, {
        description: notified !== null ? `${notified} eligible ${notified === 1 ? "student was" : "students were"} notified.` : undefined,
      });
    } catch (error) {
      setPending(null);
      const reloadable = error instanceof ApiError && (error.isVersionConflict || error.code === "drive_status_conflict");
      setProblem({ action: info, lines: problemLines(error), reloadable });
    }
  };

  return (
    <section aria-labelledby="drive-panel-heading" className="rounded-lg border border-border bg-card p-4">
      <div className="flex items-center justify-between gap-2">
        <h2 id="drive-panel-heading" className="text-xs font-medium text-muted-foreground">
          Drive
        </h2>
        <DriveStatusBadge status={status} />
      </div>
      <dl className="mt-3 flex flex-col gap-2 text-[13px]">
        <div className="flex items-start gap-2">
          <CalendarClock className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" aria-hidden />
          <div className="min-w-0">
            <dt className="sr-only">Registration window</dt>
            <dd>
              {opensAt ? <span className="block text-xs text-muted-foreground">Opens {formatDateTime(opensAt)}</span> : null}
              <span className="block">{closesAt ? `Closes ${formatDateTime(closesAt)}` : "No closing time set"}</span>
              {closes && !closes.past ? (
                <span className={cn("block text-xs", closes.urgent ? "text-warning-soft-foreground" : "text-muted-foreground")}>{closes.label}</span>
              ) : null}
            </dd>
          </div>
        </div>
      </dl>

      {canManage && actions.length > 0 ? (
        <div className="mt-4 flex flex-col gap-2">
          {primary.map((info, index) => (
            <Button
              key={info.action}
              size="sm"
              variant={index === 0 ? "default" : "outline"}
              className="w-full"
              disabled={lifecycle.isPending}
              onClick={() => setPending(info)}
            >
              {info.action === "publish" ? <Megaphone /> : null}
              {info.label}
            </Button>
          ))}
          {destructive.map((info) => (
            <Button
              key={info.action}
              size="sm"
              variant="destructive-outline"
              className="w-full"
              disabled={lifecycle.isPending}
              onClick={() => setPending(info)}
            >
              {info.label}
            </Button>
          ))}
        </div>
      ) : !canManage ? (
        <p className="mt-3 text-xs text-muted-foreground">Placement officers publish, close and complete drives.</p>
      ) : null}

      {problem ? (
        <div role="alert" className="mt-3 rounded-md border border-destructive/20 bg-destructive-soft px-3 py-2.5 text-[13px] text-destructive-soft-foreground">
          <p className="flex items-center gap-1.5 font-medium">
            <AlertTriangle className="size-3.5" /> Couldn&apos;t {problem.action.label.toLowerCase()}
          </p>
          <ul className="mt-1 list-disc pl-5 text-xs">
            {problem.lines.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
          {problem.reloadable ? (
            <Button
              variant="outline"
              size="xs"
              className="mt-2"
              onClick={() => {
                setProblem(null);
                void reload.refetch();
              }}
            >
              <RotateCw /> Reload drive
            </Button>
          ) : null}
        </div>
      ) : null}

      <AlertDialog open={Boolean(pending)} onOpenChange={(open) => !open && !lifecycle.isPending && setPending(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{pending ? `${pending.label}?` : ""}</AlertDialogTitle>
            <AlertDialogDescription>
              {pending ? (
                <>
                  “{stringValue(record, "title") ?? "This drive"}” moves from {driveStatusLabel(status).toLowerCase()} to{" "}
                  {driveStatusLabel(pending.to).toLowerCase()}. {pending.confirm}
                </>
              ) : null}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={lifecycle.isPending}>Keep as is</AlertDialogCancel>
            <Button
              variant={pending?.destructive ? "destructive" : "default"}
              disabled={lifecycle.isPending}
              onClick={() => pending && void run(pending)}
            >
              {lifecycle.isPending ? <Spinner className="text-current" /> : null}
              {pending?.label}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </section>
  );
}
