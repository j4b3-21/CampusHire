"use client";

import * as React from "react";
import Link from "next/link";
import { toast } from "sonner";
import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  CalendarPlus,
  ChevronsUpDown,
  Download,
  Eye,
  FileX,
  GitBranch,
  MoreHorizontal,
  Search,
  SearchX,
  Upload,
  Users,
  X,
} from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { ErrorState } from "@/components/common/error-state";
import { PipelineResultDialog } from "@/components/drives/pipeline-results";
import { ScheduleInterviewDialog } from "@/components/drives/schedule-interview-dialog";
import { ShortlistUploadDialog } from "@/components/drives/shortlist-upload-dialog";
import { ExportMenu } from "@/components/records/export-menu";
import { StateBadge, WorkflowValue } from "@/components/workflow/state-badge";
import { TransitionDialog } from "@/components/workflow/transition-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Pagination } from "@/components/ui/pagination";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { downloadApplicantResume, errorMessage, useApplicants, useApplicantTransition, useRecords, useWorkflow } from "@/lib/api";
import { reasonLabel, stageLabel } from "@/lib/campushire";
import { recordTitle } from "@/lib/metadata/fields";
import { formatDateTime, formatInteger, formatRelative } from "@/lib/metadata/format";
import { recordPath } from "@/lib/navigation";
import { cn } from "@/lib/utils";
import { reachableTargets } from "@/lib/workflow";
import type { Applicant, ApplicantSort, PipelineResult } from "@/types/campushire";
import type { RecordFilter } from "@/types/records";
import type { WorkflowState } from "@/types/workflow";

const PAGE_SIZE = 25;
const ALL = "__all";

function useDepartmentNames(orgId: string, enabled: boolean) {
  const { moduleByKey } = useOrg();
  const records = useRecords(orgId, "department", { size: 100 }, { enabled });
  const definition = moduleByKey("department");
  return React.useMemo(
    () => new Map((records.data?.items ?? []).map((record) => [record.id, recordTitle(definition, record)])),
    [records.data, definition]
  );
}

function SortHeader({
  label,
  field,
  sort,
  onSort,
  numeric,
}: {
  label: string;
  field: ApplicantSort;
  sort: string;
  onSort: (sort: string) => void;
  numeric?: boolean;
}) {
  const active = sort === field || sort === `-${field}`;
  const desc = sort === `-${field}`;
  return (
    <TableHead aria-sort={active ? (desc ? "descending" : "ascending") : undefined} className={cn(numeric && "text-right")}>
      <button
        type="button"
        onClick={() => onSort(!active ? field : desc ? "rollNumber" : `-${field}`)}
        className={cn(
          "focus-ring -mx-1 inline-flex items-center gap-1 rounded-sm px-1 py-0.5 transition-colors hover:text-foreground",
          active && "text-foreground",
          numeric && "flex-row-reverse"
        )}
      >
        {label}
        {active ? desc ? <ArrowDown className="size-3" /> : <ArrowUp className="size-3" /> : <ChevronsUpDown className="size-3 opacity-40" />}
      </button>
    </TableHead>
  );
}

/**
 * Applicants of one drive (`GET .../drives/{id}/applicants`): staff see their scope, recruiters their company. Bulk
 * moves, shortlist upload, resume download, export and interview scheduling depend on the caller's capabilities.
 */
export function ApplicantsPanel({ driveId, companyId }: { driveId: string; companyId: string | null }) {
  const { orgId, capabilities, access, isAdmin } = useOrg();
  const [search, setSearch] = React.useState("");
  const q = useDebouncedValue(search.trim(), 300);
  const [stage, setStage] = React.useState<string>(ALL);
  const [sort, setSort] = React.useState<string>("rollNumber");
  const [page, setPage] = React.useState(0);
  const params = React.useMemo(
    () => ({ stage: stage === ALL ? undefined : stage, q: q || undefined, sort, page, size: PAGE_SIZE }),
    [stage, q, sort, page]
  );
  const applicants = useApplicants(orgId, driveId, params);
  const workflow = useWorkflow(orgId, "application");
  const transition = useApplicantTransition(orgId, driveId);
  const departments = useDepartmentNames(orgId, capabilities("department").read);
  const canMove = capabilities("application").update;
  const canSchedule = capabilities("interview").create;
  const canOpenApplication = capabilities("application").read;

  const scope = JSON.stringify(params);
  const [selection, setSelection] = React.useState<{ scope: string; ids: Set<string> }>({ scope: "", ids: new Set() });
  const selected = selection.scope === scope ? selection.ids : new Set<string>();
  const [moveTarget, setMoveTarget] = React.useState<WorkflowState | null>(null);
  const [result, setResult] = React.useState<{ data: PipelineResult; target: WorkflowState } | null>(null);
  const [uploadOpen, setUploadOpen] = React.useState(false);
  const [scheduleFor, setScheduleFor] = React.useState<Applicant | null>(null);
  const [downloading, setDownloading] = React.useState<string | null>(null);

  const states = workflow.data?.definition.states ?? [];
  const targets = React.useMemo(() => (workflow.data ? reachableTargets(workflow.data, isAdmin, access) : []), [workflow.data, isAdmin, access]);
  const rows = applicants.data?.items ?? [];
  const pageData = applicants.data;
  const allSelected = rows.length > 0 && rows.every((row) => selected.has(row.applicationId));
  const someSelected = rows.some((row) => selected.has(row.applicationId));

  const resetPage = <T,>(setter: (value: T) => void) => (value: T) => {
    setter(value);
    setPage(0);
  };

  const toggle = (id: string, checked: boolean) =>
    setSelection((current) => {
      const ids = new Set(current.scope === scope ? current.ids : []);
      if (checked) ids.add(id);
      else ids.delete(id);
      return { scope, ids };
    });

  const toggleAll = (checked: boolean) => setSelection({ scope, ids: checked ? new Set(rows.map((row) => row.applicationId)) : new Set() });
  const clear = () => setSelection({ scope, ids: new Set() });

  const move = async (target: WorkflowState, comment: string) => {
    const data = await transition.mutateAsync({ applicationIds: [...selected], to: target.key, comment });
    setMoveTarget(null);
    if (data.failed === 0 && data.unknownRollNumbers.length === 0) {
      toast.success(`Moved ${data.succeeded} ${data.succeeded === 1 ? "applicant" : "applicants"} to ${target.label}`);
      clear();
    } else {
      setResult({ data, target });
    }
  };

  const resume = async (applicant: Applicant) => {
    setDownloading(applicant.applicationId);
    try {
      await downloadApplicantResume(orgId, driveId, applicant);
    } catch (error) {
      toast.error("Couldn't download the resume", { description: errorMessage(error) });
    } finally {
      setDownloading(null);
    }
  };

  const exportFilters: RecordFilter[] = [{ field: "drive", op: "eq", value: driveId }];
  if (stage !== ALL) exportFilters.push({ field: "stage", op: "eq", value: stage });
  const hasQuery = Boolean(q || stage !== ALL);
  const requireComment = (target: WorkflowState) =>
    workflow.data?.definition.transitions.some((item) => item.to === target.key && item.requireComment) ?? false;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative w-full sm:w-64">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            type="search"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(0);
            }}
            placeholder="Search roll no., name or email…"
            aria-label="Search applicants"
            className="h-8 pl-8 pr-7 text-[13px] [&::-webkit-search-cancel-button]:hidden"
          />
          {search ? (
            <button
              type="button"
              aria-label="Clear search"
              onClick={() => setSearch("")}
              className="focus-ring absolute right-1.5 top-1/2 -translate-y-1/2 rounded-sm p-0.5 text-muted-foreground hover:text-foreground"
            >
              <X className="size-3.5" />
            </button>
          ) : null}
        </div>
        <Select value={stage} onValueChange={resetPage(setStage)}>
          <SelectTrigger size="sm" className="h-8 w-40" aria-label="Filter by stage">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>All stages</SelectItem>
            {states.map((state) => (
              <SelectItem key={state.key} value={state.key}>
                {state.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <div className="ml-auto flex items-center gap-1.5">
          {canMove && workflow.data && targets.length > 0 ? (
            <Button variant="outline" size="sm" onClick={() => setUploadOpen(true)}>
              <Upload /> <span className="hidden sm:inline">Upload shortlist</span>
            </Button>
          ) : null}
          {capabilities("application").read ? (
            <ExportMenu orgId={orgId} moduleKey="application" params={{ filters: exportFilters, sort: "-created_at" }} hint="Applications of this drive" />
          ) : null}
        </div>
      </div>

      {selected.size > 0 && canMove ? (
        <div role="region" aria-label="Bulk actions" className="flex h-10 items-center gap-2 rounded-lg border border-primary/25 bg-primary-soft/60 px-3 text-[13px] animate-pop-in">
          <span className="font-medium text-primary-soft-foreground tabular">{selected.size} selected</span>
          <Button variant="subtle" size="xs" onClick={clear}>
            Clear
          </Button>
          <div className="ml-auto">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="xs" disabled={transition.isPending || targets.length === 0}>
                  {transition.isPending ? <Spinner className="size-3.5" /> : <GitBranch />} Move to…
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="min-w-52">
                <DropdownMenuLabel>Move {selected.size} selected to</DropdownMenuLabel>
                {targets.map((state) => (
                  <DropdownMenuItem key={state.key} onSelect={() => setMoveTarget(state)}>
                    <StateBadge state={state} />
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
      ) : null}

      <div className="relative overflow-hidden rounded-lg border border-border bg-card">
        {applicants.isFetching && applicants.data ? (
          <div className="absolute inset-x-0 top-0 z-10 h-0.5 overflow-hidden bg-primary/10" aria-hidden>
            <div className="h-full w-1/3 animate-[shimmer_1s_ease-in-out_infinite] bg-primary/60" />
          </div>
        ) : null}
        {applicants.isError && !applicants.data ? (
          <ErrorState error={applicants.error} onRetry={() => void applicants.refetch()} />
        ) : (
          <Table aria-label="Applicants" aria-busy={applicants.isFetching || undefined}>
            <TableHeader>
              <tr>
                {canMove ? (
                  <TableHead className="w-10 pr-0">
                    <Checkbox
                      aria-label="Select all applicants on this page"
                      checked={allSelected ? true : someSelected ? "indeterminate" : false}
                      onCheckedChange={(checked) => toggleAll(checked === true)}
                    />
                  </TableHead>
                ) : null}
                <SortHeader label="Roll no." field="rollNumber" sort={sort} onSort={resetPage(setSort)} />
                <SortHeader label="Name" field="fullName" sort={sort} onSort={resetPage(setSort)} />
                <TableHead className="hidden md:table-cell">Department</TableHead>
                <SortHeader label="CGPA" field="cgpa" sort={sort} onSort={resetPage(setSort)} numeric />
                <TableHead className="hidden text-right sm:table-cell">Backlogs</TableHead>
                <SortHeader label="Stage" field="stage" sort={sort} onSort={resetPage(setSort)} />
                <SortHeader label="Applied" field="appliedAt" sort={sort} onSort={resetPage(setSort)} />
                <TableHead className="w-20">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </tr>
            </TableHeader>
            <TableBody className={cn(applicants.isFetching && applicants.data && "opacity-70 transition-opacity")}>
              {applicants.isLoading ? (
                Array.from({ length: 6 }, (_, i) => (
                  <TableRow key={`skeleton-${i}`} className="hover:bg-transparent">
                    {Array.from({ length: canMove ? 9 : 8 }, (_, j) => (
                      <TableCell key={j} className="h-11">
                        <Skeleton className={cn("h-3.5", j === 2 ? "w-32" : "w-14")} />
                      </TableCell>
                    ))}
                  </TableRow>
                ))
              ) : rows.length === 0 ? (
                <tr>
                  <td colSpan={canMove ? 9 : 8}>
                    {hasQuery ? (
                      <EmptyState
                        icon={SearchX}
                        title="No applicants match"
                        description="Try another search or stage."
                        action={
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => {
                              setSearch("");
                              setStage(ALL);
                            }}
                          >
                            Clear filters
                          </Button>
                        }
                      />
                    ) : (
                      <EmptyState icon={Users} title="No applicants yet" description="Students show up here as soon as they apply. New applications appear within a second." />
                    )}
                  </td>
                </tr>
              ) : (
                rows.map((row) => {
                  const ineligible = row.eligibleNow && !row.eligibleNow.eligible;
                  return (
                    <TableRow key={row.applicationId} data-state={selected.has(row.applicationId) ? "selected" : undefined}>
                      {canMove ? (
                        <TableCell className="h-11 w-10 pr-0">
                          <Checkbox
                            aria-label={`Select ${row.student.fullName}`}
                            checked={selected.has(row.applicationId)}
                            onCheckedChange={(checked) => toggle(row.applicationId, checked === true)}
                          />
                        </TableCell>
                      ) : null}
                      <TableCell className="h-11 whitespace-nowrap font-mono text-xs">{row.student.rollNumber}</TableCell>
                      <TableCell className="h-11 max-w-56">
                        <div className="flex items-center gap-1.5">
                          <span className="truncate font-medium">{row.student.fullName}</span>
                          {ineligible ? (
                            <SimpleTooltip
                              label={
                                <span className="flex flex-col gap-0.5">
                                  <span>Not eligible any more:</span>
                                  {row.eligibleNow?.reasons.map((reason) => (
                                    <span key={reason.code}>· {reason.message || reasonLabel(reason.code)}</span>
                                  ))}
                                </span>
                              }
                            >
                              <AlertTriangle className="size-3.5 shrink-0 text-warning" aria-label="Not eligible any more" />
                            </SimpleTooltip>
                          ) : null}
                        </div>
                        {row.student.email ? <span className="block truncate text-xs text-muted-foreground">{row.student.email}</span> : null}
                      </TableCell>
                      <TableCell className="hidden h-11 max-w-44 truncate text-muted-foreground md:table-cell">
                        {row.student.departmentId ? departments.get(row.student.departmentId) ?? "–" : "–"}
                      </TableCell>
                      <TableCell className="h-11 text-right tabular">{row.student.cgpa ?? "–"}</TableCell>
                      <TableCell className="hidden h-11 text-right tabular sm:table-cell">
                        {row.student.activeBacklogs ?? 0}
                        <span className="text-muted-foreground">/{row.student.totalBacklogs ?? 0}</span>
                      </TableCell>
                      <TableCell className="h-11">
                        {states.length ? <WorkflowValue states={states} value={row.stage} /> : <Badge variant="neutral">{stageLabel(row.stage)}</Badge>}
                      </TableCell>
                      <TableCell className="h-11 whitespace-nowrap text-muted-foreground">
                        <SimpleTooltip label={formatDateTime(row.appliedAt)}>
                          <span>{formatRelative(row.appliedAt)}</span>
                        </SimpleTooltip>
                      </TableCell>
                      <TableCell className="h-11">
                        <div className="flex items-center justify-end gap-0.5">
                          {row.student.resume ? (
                            <SimpleTooltip label={`Download resume (${row.student.resume.name ?? "file"})`}>
                              <Button
                                variant="ghost"
                                size="icon-xs"
                                aria-label={`Download resume of ${row.student.fullName}`}
                                disabled={downloading === row.applicationId}
                                onClick={() => void resume(row)}
                              >
                                {downloading === row.applicationId ? <Spinner className="size-3.5" /> : <Download />}
                              </Button>
                            </SimpleTooltip>
                          ) : (
                            <SimpleTooltip label="No resume uploaded">
                              <span className="grid size-7 place-items-center text-subtle-foreground">
                                <FileX className="size-3.5" aria-label="No resume" />
                              </span>
                            </SimpleTooltip>
                          )}
                          {canOpenApplication || canSchedule ? (
                            <DropdownMenu>
                              <DropdownMenuTrigger asChild>
                                <Button variant="ghost" size="icon-xs" aria-label={`More actions for ${row.student.fullName}`}>
                                  <MoreHorizontal />
                                </Button>
                              </DropdownMenuTrigger>
                              <DropdownMenuContent align="end">
                                {canOpenApplication ? (
                                  <DropdownMenuItem asChild>
                                    <Link href={recordPath(orgId, "application", row.applicationId)}>
                                      <Eye /> Open application
                                    </Link>
                                  </DropdownMenuItem>
                                ) : null}
                                {canSchedule ? (
                                  <>
                                    <DropdownMenuSeparator />
                                    <DropdownMenuItem onSelect={() => setScheduleFor(row)}>
                                      <CalendarPlus /> Schedule interview
                                    </DropdownMenuItem>
                                  </>
                                ) : null}
                              </DropdownMenuContent>
                            </DropdownMenu>
                          ) : null}
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })
              )}
            </TableBody>
          </Table>
        )}
      </div>

      {pageData && pageData.totalElements > 0 ? (
        <div className="flex flex-wrap items-center justify-between gap-3 text-[13px] text-muted-foreground">
          <span className="tabular">
            {formatInteger(pageData.page * pageData.size + 1)}–{formatInteger(pageData.page * pageData.size + pageData.items.length)} of{" "}
            {formatInteger(pageData.totalElements)} applicants
          </span>
          <Pagination page={pageData.page} totalPages={pageData.totalPages} onPageChange={setPage} />
        </div>
      ) : null}

      {moveTarget ? (
        <TransitionDialog
          open
          onOpenChange={(open) => !open && setMoveTarget(null)}
          title={`Move ${selected.size} ${selected.size === 1 ? "applicant" : "applicants"}`}
          subject={`${selected.size} selected`}
          note="Each application moves on its own; applicants that can't make this move are listed afterwards."
          target={moveTarget}
          requireComment={requireComment(moveTarget)}
          pending={transition.isPending}
          confirmLabel={`Move ${selected.size}`}
          onConfirm={(comment) => move(moveTarget, comment)}
        />
      ) : null}

      {workflow.data ? (
        <ShortlistUploadDialog
          open={uploadOpen}
          onOpenChange={setUploadOpen}
          driveId={driveId}
          workflow={workflow.data}
          targets={targets}
          onResult={(data, target) => {
            if (data.failed === 0 && data.unknownRollNumbers.length === 0) {
              toast.success(`Moved ${data.succeeded} ${data.succeeded === 1 ? "applicant" : "applicants"} to ${target.label}`);
            } else {
              setResult({ data, target });
            }
          }}
        />
      ) : null}

      <PipelineResultDialog
        result={result?.data ?? null}
        target={result?.target}
        applicants={rows}
        onClose={() => {
          setResult(null);
          clear();
        }}
      />

      <ScheduleInterviewDialog applicant={scheduleFor} driveId={driveId} companyId={companyId} onClose={() => setScheduleFor(null)} />
    </div>
  );
}
