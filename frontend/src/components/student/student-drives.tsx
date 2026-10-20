"use client";

import * as React from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { toast } from "sonner";
import {
  Briefcase,
  Building2,
  CalendarDays,
  CalendarRange,
  CheckCircle2,
  ChevronDown,
  Clock,
  IndianRupee,
  MapPin,
  ShieldAlert,
  ShieldCheck,
  Undo2,
} from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { ErrorState } from "@/components/common/error-state";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { WorkflowValue } from "@/components/workflow/state-badge";
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
import { Label } from "@/components/ui/label";
import { Pagination } from "@/components/ui/pagination";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { ApiError, useApplyToDrive, useStudentDrives, useWithdrawFromDrive, useWorkflow } from "@/lib/api";
import { WITHDRAWABLE_STAGES, countdown, formatLpa, reasonLabel, stageLabel } from "@/lib/campushire";
import { formatDate, formatDateTime, humanize } from "@/lib/metadata/format";
import { cn } from "@/lib/utils";
import type { StudentDrive, StudentDriveFilter } from "@/types/campushire";
import type { WorkflowState } from "@/types/workflow";

const FILTERS: { value: StudentDriveFilter; label: string; empty: string }[] = [
  { value: "open", label: "Open", empty: "No drives are open for registration right now. New ones are announced in your notifications." },
  { value: "upcoming", label: "Upcoming", empty: "No upcoming drives yet." },
  { value: "all", label: "All", empty: "No drives yet." },
];

function isFilter(value: string | null): value is StudentDriveFilter {
  return value === "open" || value === "upcoming" || value === "all";
}

function EligibilityBadge({ drive }: { drive: StudentDrive }) {
  const verdict = drive.eligibility;
  if (!verdict) return null;
  if (verdict.eligible) {
    return (
      <Badge variant="success">
        <ShieldCheck /> Eligible
      </Badge>
    );
  }
  const reasons = verdict.reasons.map((reason) => reason.message || reasonLabel(reason.code));
  return (
    <SimpleTooltip
      label={
        <span className="flex max-w-64 flex-col gap-0.5">
          {reasons.map((reason) => (
            <span key={reason}>· {reason}</span>
          ))}
        </span>
      }
    >
      <Badge variant="destructive" tabIndex={0} className="focus-ring cursor-help">
        <ShieldAlert /> Not eligible
      </Badge>
    </SimpleTooltip>
  );
}

function Fact({ icon: Icon, children, className }: { icon: typeof Clock; children: React.ReactNode; className?: string }) {
  return (
    <span className={cn("inline-flex min-w-0 items-center gap-1.5 text-[13px] text-muted-foreground", className)}>
      <Icon className="size-3.5 shrink-0" aria-hidden />
      <span className="truncate">{children}</span>
    </span>
  );
}

function DriveCard({
  drive,
  states,
  highlighted,
  onApply,
  onWithdraw,
  applying,
}: {
  drive: StudentDrive;
  states: WorkflowState[];
  highlighted: boolean;
  onApply: (drive: StudentDrive) => void;
  onWithdraw: (drive: StudentDrive) => void;
  applying: boolean;
}) {
  const [expanded, setExpanded] = React.useState(highlighted);
  const ref = React.useRef<HTMLElement>(null);
  const application = drive.application;
  const upcoming = !drive.registrationOpen && drive.driveStatus === "published" && Boolean(drive.registrationOpensAt && new Date(drive.registrationOpensAt) > new Date());
  const timer = upcoming ? countdown(drive.registrationOpensAt, "Opens") : drive.registrationOpen ? countdown(drive.registrationClosesAt) : null;
  const ineligible = drive.eligibility && !drive.eligibility.eligible;
  const withdrawn = application?.stage === "withdrawn";

  React.useEffect(() => {
    if (highlighted) ref.current?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [highlighted]);

  let action: React.ReactNode;
  if (application) {
    action = (
      <div className="flex flex-wrap items-center gap-2">
        <span className="inline-flex items-center gap-1.5 text-[13px] text-muted-foreground">
          {withdrawn ? <Undo2 className="size-3.5" /> : <CheckCircle2 className="size-3.5 text-success" />}
          {withdrawn ? "Withdrawn" : "Applied"}
        </span>
        {!withdrawn && application.stage !== "applied" ? (
          states.length ? (
            <WorkflowValue states={states} value={application.stage} />
          ) : (
            <Badge variant="neutral">{stageLabel(application.stage)}</Badge>
          )
        ) : null}
        {WITHDRAWABLE_STAGES.includes(application.stage) ? (
          <Button variant="outline" size="sm" onClick={() => onWithdraw(drive)}>
            Withdraw
          </Button>
        ) : null}
      </div>
    );
  } else if (drive.registrationOpen) {
    action = (
      <Button className="w-full sm:w-auto" disabled={Boolean(ineligible) || applying} onClick={() => onApply(drive)}>
        {applying ? <Spinner className="text-primary-foreground" /> : null}
        {ineligible ? "Not eligible" : "Apply"}
      </Button>
    );
  } else if (upcoming) {
    action = <span className="text-[13px] text-muted-foreground">Registration opens {drive.registrationOpensAt ? formatDateTime(drive.registrationOpensAt) : "soon"}</span>;
  } else {
    action = <span className="text-[13px] text-muted-foreground">Registration closed</span>;
  }

  return (
    <article
      ref={ref}
      aria-labelledby={`drive-${drive.id}`}
      className={cn(
        "flex min-w-0 flex-col rounded-lg border border-border bg-card shadow-xs transition-colors",
        highlighted && "border-primary/50 ring-2 ring-primary/20"
      )}
    >
      <div className="flex flex-col gap-3 p-4">
        <div className="flex items-start gap-3">
          <span className="grid size-10 shrink-0 place-items-center rounded-lg border border-border bg-surface text-sm font-semibold text-muted-foreground">
            {(drive.company?.name ?? "?").charAt(0).toUpperCase()}
          </span>
          <div className="min-w-0 flex-1">
            <h3 id={`drive-${drive.id}`} className="truncate text-[15px] font-semibold tracking-[-0.01em]">
              {drive.job?.title ?? drive.title}
            </h3>
            <p className="truncate text-[13px] text-muted-foreground">
              {drive.company?.name ?? "Company"} · {drive.title}
            </p>
          </div>
          <EligibilityBadge drive={drive} />
        </div>

        <div className="grid grid-cols-[minmax(0,1fr)] gap-x-4 gap-y-1.5 sm:grid-cols-[repeat(2,minmax(0,1fr))]">
          <Fact icon={IndianRupee} className="font-medium text-foreground">
            {drive.job?.ctc ? formatLpa(drive.job.ctc) : "CTC not disclosed"}
          </Fact>
          <Fact icon={Briefcase}>{drive.job?.jobType ? humanize(drive.job.jobType) : "Role"}</Fact>
          <Fact icon={MapPin}>{drive.job?.location ?? (drive.mode ? humanize(drive.mode) : "Location to be announced")}</Fact>
          <Fact icon={CalendarDays}>{drive.driveDate ? `Drive on ${formatDate(drive.driveDate)}` : "Drive date to be announced"}</Fact>
          {drive.registrationClosesAt ? <Fact icon={Clock}>Registration closes {formatDateTime(drive.registrationClosesAt)}</Fact> : null}
        </div>

        {timer && !timer.past ? (
          <p className={cn("inline-flex w-fit items-center gap-1.5 rounded-md px-2 py-0.5 text-xs font-medium", timer.urgent ? "bg-warning-soft text-warning-soft-foreground" : "bg-muted text-muted-foreground")}>
            <Clock className="size-3" /> {timer.label}
          </p>
        ) : null}

        {ineligible && !application ? (
          <ul className="flex flex-col gap-0.5 rounded-md bg-destructive-soft px-3 py-2 text-xs text-destructive-soft-foreground" aria-label="Why you're not eligible">
            {drive.eligibility?.reasons.map((reason) => (
              <li key={reason.code}>· {reason.message || reasonLabel(reason.code)}</li>
            ))}
          </ul>
        ) : null}
      </div>

      {expanded && (drive.description || drive.venue) ? (
        <div className="border-t border-border px-4 py-3 text-[13px] leading-relaxed text-muted-foreground">
          {drive.venue ? (
            <p className="mb-2">
              <span className="font-medium text-foreground">Venue:</span> {drive.venue}
              {drive.mode ? ` (${humanize(drive.mode)})` : ""}
            </p>
          ) : null}
          {drive.description ? <p className="whitespace-pre-wrap">{drive.description}</p> : null}
        </div>
      ) : null}

      <div className="mt-auto flex flex-wrap items-center justify-between gap-2 border-t border-border px-4 py-3">
        {drive.description || drive.venue ? (
          <Button variant="subtle" size="sm" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
            Details <ChevronDown className={cn("transition-transform", expanded && "rotate-180")} />
          </Button>
        ) : (
          <span />
        )}
        {action}
      </div>
    </article>
  );
}

function WithdrawDialog({ drive, onClose }: { drive: StudentDrive | null; onClose: () => void }) {
  const { orgId } = useOrg();
  const withdraw = useWithdrawFromDrive(orgId);
  const [comment, setComment] = React.useState("");
  const confirm = async () => {
    if (!drive) return;
    try {
      await withdraw.mutateAsync({ driveId: drive.id, comment });
      toast.success("Application withdrawn", { description: drive.company?.name ?? drive.title });
      setComment("");
      onClose();
    } catch (error) {
      toast.error("Couldn't withdraw", { description: error instanceof ApiError ? error.detail ?? error.friendlyMessage : undefined });
    }
  };
  return (
    <AlertDialog open={Boolean(drive)} onOpenChange={(open) => !open && !withdraw.isPending && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Withdraw your application?</AlertDialogTitle>
          <AlertDialogDescription>
            You&apos;ll be removed from {drive?.company?.name ?? "this drive"}&apos;s process. You can&apos;t apply to this drive again afterwards.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <div className="grid gap-1.5">
          <Label htmlFor="withdraw-reason">
            Reason <span className="font-normal text-muted-foreground">(optional)</span>
          </Label>
          <Textarea id="withdraw-reason" value={comment} maxLength={2000} onChange={(event) => setComment(event.target.value)} placeholder="e.g. Going for higher studies" />
        </div>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={withdraw.isPending}>Keep application</AlertDialogCancel>
          <Button variant="destructive" disabled={withdraw.isPending} onClick={() => void confirm()}>
            {withdraw.isPending ? <Spinner className="text-current" /> : null}
            Withdraw
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** Drives for students: eligibility per drive, apply, applied stage, withdraw. */
export function StudentDrives() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { orgId } = useOrg();
  const tabParam = searchParams.get("tab");
  const filter: StudentDriveFilter = isFilter(tabParam) ? tabParam : "open";
  const highlight = searchParams.get("drive");
  const [page, setPage] = React.useState(0);
  const drives = useStudentDrives(orgId, filter, page, 12);
  const workflow = useWorkflow(orgId, "application");
  const apply = useApplyToDrive(orgId);
  const [withdrawing, setWithdrawing] = React.useState<StudentDrive | null>(null);
  const states = workflow.data?.definition.states ?? [];
  const items = drives.data?.items ?? [];
  const config = FILTERS.find((item) => item.value === filter) ?? FILTERS[0];

  const setFilter = (value: string) => {
    if (!isFilter(value)) return;
    setPage(0);
    router.replace(`${pathname}?tab=${value}`, { scroll: false });
  };

  const onApply = async (drive: StudentDrive) => {
    try {
      const application = await apply.mutateAsync(drive.id);
      toast.success(`Applied to ${drive.company?.name ?? drive.title}`, {
        description: application.stage === "applied" ? "Good luck! Track your stage under My applications." : `Your application is at ${stageLabel(application.stage)}.`,
      });
    } catch (error) {
      if (error instanceof ApiError && error.code === "not_eligible") {
        toast.error("You're not eligible for this drive", { description: error.errors.map((violation) => violation.message).join(" ") || error.detail });
      } else {
        toast.error("Couldn't apply", { description: error instanceof ApiError ? error.friendlyMessage : undefined });
      }
      void drives.refetch();
    }
  };

  return (
    <>
      <PageHeader title="Drives" description="Placement drives from your institute. Apply before registration closes." />
      <PageBody className="flex flex-col gap-4">
        <Tabs value={filter} onValueChange={setFilter}>
          <TabsList aria-label="Drive filter">
            {FILTERS.map((item) => (
              <TabsTrigger key={item.value} value={item.value}>
                {item.label}
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>
        {drives.isLoading ? (
          <div className="grid gap-4 md:grid-cols-2 2xl:grid-cols-3">
            {Array.from({ length: 4 }, (_, i) => (
              <Skeleton key={i} className="h-56 rounded-lg" />
            ))}
          </div>
        ) : drives.isError ? (
          <ErrorState error={drives.error} onRetry={() => void drives.refetch()} />
        ) : items.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border-strong">
            <EmptyState icon={CalendarRange} title={`No ${config.label.toLowerCase()} drives`} description={config.empty} />
          </div>
        ) : (
          <div className={cn("grid grid-cols-[minmax(0,1fr)] gap-4 md:grid-cols-2 2xl:grid-cols-3", drives.isFetching && "opacity-80 transition-opacity")}>
            {items.map((drive) => (
              <DriveCard
                key={drive.id}
                drive={drive}
                states={states}
                highlighted={drive.id === highlight}
                applying={apply.isPending && apply.variables === drive.id}
                onApply={(target) => void onApply(target)}
                onWithdraw={setWithdrawing}
              />
            ))}
          </div>
        )}
        {drives.data && drives.data.totalPages > 1 ? (
          <div className="flex justify-end">
            <Pagination page={drives.data.page} totalPages={drives.data.totalPages} onPageChange={setPage} />
          </div>
        ) : null}
        <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <Building2 className="size-3.5" /> Eligibility is checked again when you apply, against your current profile.
        </p>
      </PageBody>
      <WithdrawDialog drive={withdrawing} onClose={() => setWithdrawing(null)} />
    </>
  );
}
