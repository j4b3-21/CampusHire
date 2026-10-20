"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { toast } from "sonner";
import {
  ArrowLeft,
  ArrowRight,
  Ban,
  Check,
  CheckCircle2,
  CircleAlert,
  CircleDashed,
  Download,
  FileSpreadsheet,
  FileText,
  FlaskConical,
  History,
  Lock,
  RotateCcw,
  UploadCloud,
  X,
  XCircle,
} from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { ErrorState } from "@/components/common/error-state";
import { ModuleGlyph } from "@/components/common/module-glyph";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Label } from "@/components/ui/label";
import { Progress } from "@/components/ui/progress";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Select, SelectContent, SelectItem, SelectSeparator, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import {
  ApiError,
  downloadImportErrors,
  downloadImportTemplate,
  errorMessage,
  useCancelImport,
  useImportJob,
  useImportJobs,
  usePreviewImport,
  useStartImport,
} from "@/lib/api";
import { violationMap } from "@/lib/api/violations";
import { formatBytes, formatDateTime, formatInteger, formatRelative, humanize } from "@/lib/metadata/format";
import { modulePath } from "@/lib/navigation";
import { cn } from "@/lib/utils";
import {
  FINISHED_IMPORT_STATUSES,
  type ExchangeFormat,
  type ImportJob,
  type ImportMapping,
  type ImportMode,
  type ImportPreview,
  type ImportStatus,
} from "@/types/data-exchange";

const STEPS = [
  { key: "module", label: "Module" },
  { key: "upload", label: "Upload" },
  { key: "map", label: "Map columns" },
  { key: "review", label: "Review" },
  { key: "run", label: "Import" },
] as const;

type Step = (typeof STEPS)[number]["key"];

const IGNORE = "__ignore";
const ACCEPT = ".xlsx,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv";
const MAX_BYTES = 25 * 1024 * 1024;

const STATUS_STYLES: Record<ImportStatus, { variant: "neutral" | "default" | "success" | "destructive" | "warning"; label: string }> = {
  QUEUED: { variant: "neutral", label: "Queued" },
  RUNNING: { variant: "default", label: "Running" },
  COMPLETED: { variant: "success", label: "Completed" },
  FAILED: { variant: "destructive", label: "Failed" },
  CANCELLED: { variant: "warning", label: "Cancelled" },
};

function StatusBadge({ job }: { job: Pick<ImportJob, "status" | "dryRun"> }) {
  const style = STATUS_STYLES[job.status] ?? STATUS_STYLES.QUEUED;
  return (
    <span className="inline-flex items-center gap-1">
      <Badge variant={style.variant}>
        {job.status === "RUNNING" ? <Spinner className="size-3 text-current" /> : null}
        {style.label}
      </Badge>
      {job.dryRun ? (
        <Badge variant="outline">
          <FlaskConical /> Dry run
        </Badge>
      ) : null}
    </span>
  );
}

function Stepper({ step, reachable, onStep }: { step: Step; reachable: (step: Step) => boolean; onStep: (step: Step) => void }) {
  const current = STEPS.findIndex((item) => item.key === step);
  return (
    <ol className="flex items-center gap-1 overflow-x-auto pb-1 [scrollbar-width:none]" aria-label="Import steps">
      {STEPS.map((item, index) => {
        const done = index < current;
        const active = index === current;
        const enabled = reachable(item.key) && !active;
        return (
          <li key={item.key} className="flex items-center gap-1">
            {index > 0 ? <span aria-hidden className={cn("h-px w-5 sm:w-8", done || active ? "bg-primary/50" : "bg-border")} /> : null}
            <button
              type="button"
              disabled={!enabled}
              onClick={() => onStep(item.key)}
              aria-current={active ? "step" : undefined}
              className={cn(
                "focus-ring flex items-center gap-2 rounded-full px-2 py-1 text-[13px] whitespace-nowrap transition-colors",
                active ? "font-medium text-foreground" : done ? "text-foreground/80 hover:bg-accent" : "text-muted-foreground",
                !enabled && !active && "cursor-default"
              )}
            >
              <span
                className={cn(
                  "grid size-5 place-items-center rounded-full border text-[11px] font-medium tabular",
                  active ? "border-primary bg-primary text-primary-foreground" : done ? "border-primary/40 bg-primary-soft text-primary-soft-foreground" : "border-border-strong"
                )}
              >
                {done ? <Check className="size-3" strokeWidth={3} /> : index + 1}
              </span>
              {item.label}
            </button>
          </li>
        );
      })}
    </ol>
  );
}

function StepCard({ title, description, children, footer }: { title: string; description?: React.ReactNode; children: React.ReactNode; footer?: React.ReactNode }) {
  return (
    <section className="flex flex-col rounded-lg border border-border bg-card">
      <header className="border-b border-border px-5 py-4">
        <h2 className="text-sm font-semibold">{title}</h2>
        {description ? <p className="mt-0.5 text-[13px] text-muted-foreground">{description}</p> : null}
      </header>
      <div className="flex flex-col gap-5 p-5">{children}</div>
      {footer ? <footer className="flex flex-wrap items-center justify-between gap-2 border-t border-border px-5 py-3">{footer}</footer> : null}
    </section>
  );
}

function suggested(preview: ImportPreview, previous: ImportMapping | null): ImportMapping {
  const known = new Set(preview.fields.map((field) => field.key));
  const mapping: ImportMapping = {};
  for (const header of preview.headers) {
    const reused = previous?.[header];
    if (reused !== undefined && (reused === null || known.has(reused))) mapping[header] = reused;
    else mapping[header] = preview.suggestedMapping[header] ?? null;
  }
  return mapping;
}

function JobProgress({ orgId, moduleKey, jobId, onFixFile, onRealRun, starting }: {
  orgId: string;
  moduleKey: string;
  jobId: string;
  onFixFile: () => void;
  onRealRun: (() => void) | null;
  starting: boolean;
}) {
  const job = useImportJob(orgId, moduleKey, jobId);
  const cancel = useCancelImport(orgId, moduleKey);
  const [downloading, setDownloading] = React.useState<ExchangeFormat | null>(null);
  const data = job.data;

  if (job.isLoading) return <Skeleton className="h-48 w-full rounded-lg" />;
  if (job.isError || !data) return <ErrorState error={job.error} onRetry={() => void job.refetch()} />;

  const finished = FINISHED_IMPORT_STATUSES.includes(data.status);
  const total = data.totalRows ?? 0;
  const percent = total > 0 ? Math.round((data.processedRows / total) * 100) : finished ? 100 : 0;
  const would = data.dryRun ? "would be " : "";

  const errors = async (format: ExchangeFormat) => {
    setDownloading(format);
    try {
      await downloadImportErrors(orgId, moduleKey, data.id, format);
    } catch (error) {
      toast.error("Couldn't download the error file", { description: errorMessage(error) });
    } finally {
      setDownloading(null);
    }
  };

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center gap-3">
        <FileSpreadsheet className="size-5 text-muted-foreground" />
        <div className="min-w-0 flex-1">
          <p className="truncate text-[13px] font-medium">{data.fileName}</p>
          <p className="text-xs text-muted-foreground">
            {data.mode === "UPSERT" ? `Create or update, matched by ${humanize(data.matchField ?? "")}` : "Create new records"} · started{" "}
            {formatRelative(data.startedAt ?? data.createdAt)}
          </p>
        </div>
        <StatusBadge job={data} />
        {!finished ? (
          <Button variant="outline" size="sm" disabled={cancel.isPending} onClick={() => cancel.mutate(data.id)}>
            <Ban /> Cancel
          </Button>
        ) : null}
      </div>

      <div className="flex flex-col gap-1.5" aria-live="polite">
        <Progress value={percent} aria-label="Import progress" />
        <p className="text-xs text-muted-foreground tabular">
          {data.totalRows === null ? "Counting rows…" : `${formatInteger(data.processedRows)} of ${formatInteger(total)} rows processed (${percent}%)`}
        </p>
      </div>

      <dl className="grid grid-cols-3 gap-3">
        {[
          { label: `${data.dryRun ? "Would be created" : "Created"}`, value: data.createdRows, tone: "text-success-soft-foreground" },
          { label: `${data.dryRun ? "Would be updated" : "Updated"}`, value: data.updatedRows, tone: "text-primary" },
          { label: "Failed", value: data.failedRows, tone: data.failedRows > 0 ? "text-destructive" : "text-muted-foreground" },
        ].map((stat) => (
          <div key={stat.label} className="rounded-lg border border-border px-4 py-3">
            <dt className="text-xs text-muted-foreground">{stat.label}</dt>
            <dd className={cn("text-xl font-semibold tabular", stat.tone)}>{formatInteger(stat.value)}</dd>
          </div>
        ))}
      </dl>

      {data.status === "FAILED" && data.errorMessage ? (
        <div role="alert" className="flex gap-2 rounded-md border border-destructive/20 bg-destructive-soft px-3 py-2.5 text-[13px] text-destructive-soft-foreground">
          <XCircle className="mt-0.5 size-4 shrink-0" /> {data.errorMessage}
        </div>
      ) : null}

      {finished && data.status === "COMPLETED" ? (
        <div
          role="status"
          className={cn(
            "flex flex-wrap items-center gap-3 rounded-md border px-3 py-2.5 text-[13px]",
            data.failedRows > 0 ? "border-warning/30 bg-warning-soft text-warning-soft-foreground" : "border-success/20 bg-success-soft text-success-soft-foreground"
          )}
        >
          {data.failedRows > 0 ? <CircleAlert className="size-4 shrink-0" /> : <CheckCircle2 className="size-4 shrink-0" />}
          <span className="flex-1">
            {data.dryRun ? "Dry run finished: nothing was saved. " : "Import finished. "}
            {formatInteger(data.createdRows)} {would}created, {formatInteger(data.updatedRows)} {would}updated
            {data.failedRows > 0 ? `, ${formatInteger(data.failedRows)} rows need fixing.` : "."}
          </span>
          {data.dryRun && onRealRun ? (
            <Button size="sm" disabled={starting} onClick={onRealRun}>
              {starting ? <Spinner className="text-primary-foreground" /> : null}
              Run the real import
            </Button>
          ) : !data.dryRun ? (
            <Button size="sm" variant="outline" asChild>
              <Link href={modulePath(orgId, moduleKey)}>View records</Link>
            </Button>
          ) : null}
        </div>
      ) : null}

      {data.errors.length > 0 ? (
        <div className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="mr-auto text-[13px] font-semibold">
              Row errors{" "}
              <span className="font-normal text-muted-foreground">
                (first {Math.min(100, data.errors.length)}
                {data.failedRows > data.errors.length ? ` of ${formatInteger(data.failedRows)}` : ""})
              </span>
            </h3>
            {finished && data.errorReportAvailable ? (
              <>
                <Button variant="outline" size="xs" disabled={downloading !== null} onClick={() => void errors("xlsx")}>
                  {downloading === "xlsx" ? <Spinner className="size-3.5" /> : <Download />} Error file (.xlsx)
                </Button>
                <Button variant="outline" size="xs" disabled={downloading !== null} onClick={() => void errors("csv")}>
                  {downloading === "csv" ? <Spinner className="size-3.5" /> : <Download />} .csv
                </Button>
              </>
            ) : null}
            {finished ? (
              <Button size="xs" onClick={onFixFile}>
                <UploadCloud /> Import the fixed file
              </Button>
            ) : null}
          </div>
          <div className="max-h-80 overflow-auto rounded-lg border border-border">
            <Table aria-label="Row errors">
              <TableHeader className="sticky top-0 z-10">
                <tr>
                  <TableHead className="w-16 text-right">Row</TableHead>
                  <TableHead>Column</TableHead>
                  <TableHead>Problem</TableHead>
                </tr>
              </TableHeader>
              <TableBody>
                {data.errors.map((error, index) => (
                  <TableRow key={`${error.row}-${error.column}-${index}`} className="hover:bg-transparent">
                    <TableCell className="h-9 text-right font-mono text-xs tabular">{error.row}</TableCell>
                    <TableCell className="h-9 max-w-40 truncate">{error.column ?? <span className="text-muted-foreground">Whole row</span>}</TableCell>
                    <TableCell className="h-9">
                      <span>{error.message}</span> <span className="font-mono text-[11px] text-subtle-foreground">{error.code}</span>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          {finished && data.errorReportAvailable ? (
            <p className="text-xs text-muted-foreground">
              The error file holds the failed rows with an extra “error” column. Fix them and upload it as is; the extra columns are ignored.
            </p>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

function JobHistory({ orgId, moduleKey, onOpen, activeJobId }: { orgId: string; moduleKey: string; onOpen: (job: ImportJob) => void; activeJobId: string | null }) {
  const [page, setPage] = React.useState(0);
  const jobs = useImportJobs(orgId, moduleKey, page);
  const items = jobs.data?.items ?? [];
  return (
    <section aria-labelledby="history-heading" className="flex flex-col gap-3">
      <h2 id="history-heading" className="flex items-center gap-2 text-sm font-semibold">
        <History className="size-4 text-muted-foreground" /> Recent imports
      </h2>
      <div className="overflow-hidden rounded-lg border border-border bg-card">
        {jobs.isLoading ? (
          <div className="flex flex-col gap-2 p-4">
            <Skeleton className="h-8 w-full" />
            <Skeleton className="h-8 w-full" />
          </div>
        ) : jobs.isError ? (
          <ErrorState size="sm" error={jobs.error} onRetry={() => void jobs.refetch()} />
        ) : items.length === 0 ? (
          <p className="px-4 py-8 text-center text-[13px] text-muted-foreground">No imports for this module yet.</p>
        ) : (
          <Table aria-label="Recent imports">
            <TableHeader>
              <tr>
                <TableHead>File</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="hidden text-right sm:table-cell">Created</TableHead>
                <TableHead className="hidden text-right sm:table-cell">Updated</TableHead>
                <TableHead className="text-right">Failed</TableHead>
                <TableHead className="hidden md:table-cell">Started</TableHead>
              </tr>
            </TableHeader>
            <TableBody>
              {items.map((job) => (
                <TableRow
                  key={job.id}
                  data-state={job.id === activeJobId ? "selected" : undefined}
                  className="cursor-pointer"
                  onClick={() => onOpen(job)}
                >
                  <TableCell className="h-10 max-w-56">
                    <button type="button" className="focus-ring block max-w-full truncate rounded-sm text-left font-medium hover:underline" onClick={() => onOpen(job)}>
                      {job.fileName}
                    </button>
                    <span className="text-xs text-muted-foreground">{job.mode === "UPSERT" ? "Create or update" : "Create"}</span>
                  </TableCell>
                  <TableCell className="h-10">
                    <StatusBadge job={job} />
                  </TableCell>
                  <TableCell className="hidden h-10 text-right tabular sm:table-cell">{formatInteger(job.createdRows)}</TableCell>
                  <TableCell className="hidden h-10 text-right tabular sm:table-cell">{formatInteger(job.updatedRows)}</TableCell>
                  <TableCell className={cn("h-10 text-right tabular", job.failedRows > 0 && "text-destructive")}>{formatInteger(job.failedRows)}</TableCell>
                  <TableCell className="hidden h-10 whitespace-nowrap text-muted-foreground md:table-cell">
                    <SimpleTooltip label={formatDateTime(job.createdAt)}>
                      <span>{formatRelative(job.createdAt)}</span>
                    </SimpleTooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </div>
      {jobs.data && jobs.data.totalPages > 1 ? (
        <div className="flex justify-end gap-2">
          <Button variant="outline" size="xs" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Newer
          </Button>
          <Button variant="outline" size="xs" disabled={page + 1 >= jobs.data.totalPages} onClick={() => setPage(page + 1)}>
            Older
          </Button>
        </div>
      ) : null}
    </section>
  );
}

/** Excel/CSV import: module and template, upload and preview, column mapping, dry run, progress and errors. */
export function ImportWizard() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { orgId, modules, capabilities, loading } = useOrg();
  const importable = modules.filter((module) => capabilities(module.apiKey).create);
  const requested = searchParams.get("module");
  const [moduleKey, setModuleKey] = React.useState<string | null>(requested);
  const activeModule = moduleKey && importable.some((module) => module.apiKey === moduleKey) ? moduleKey : null;
  const definition = importable.find((module) => module.apiKey === activeModule);

  const [step, setStep] = React.useState<Step>(requested ? "upload" : "module");
  const [file, setFile] = React.useState<File | null>(null);
  const [preview, setPreview] = React.useState<ImportPreview | null>(null);
  const [mapping, setMapping] = React.useState<ImportMapping>({});
  const [lastMapping, setLastMapping] = React.useState<ImportMapping | null>(null);
  const [mode, setMode] = React.useState<ImportMode>("CREATE");
  const [matchField, setMatchField] = React.useState<string>("");
  const [dryRun, setDryRun] = React.useState(true);
  const [jobId, setJobId] = React.useState<string | null>(null);
  const [uploadError, setUploadError] = React.useState<string | null>(null);
  const [startError, setStartError] = React.useState<ApiError | null>(null);
  const [templatePending, setTemplatePending] = React.useState<ExchangeFormat | null>(null);
  const [dragging, setDragging] = React.useState(false);
  const inputRef = React.useRef<HTMLInputElement>(null);

  const previewMutation = usePreviewImport(orgId, activeModule ?? "");
  const start = useStartImport(orgId, activeModule ?? "");

  const effectiveStep: Step = activeModule ? step : "module";
  const fields = preview?.fields ?? [];
  const mappedKeys = Object.values(mapping).filter((value): value is string => Boolean(value));
  const duplicates = new Set(mappedKeys.filter((key, index) => mappedKeys.indexOf(key) !== index));
  const missingRequired = fields.filter((field) => field.required && !mappedKeys.includes(field.key));
  const uniqueMapped = fields.filter((field) => field.unique && mappedKeys.includes(field.key));
  const mappingValid = mappedKeys.length > 0 && duplicates.size === 0 && (mode === "UPSERT" || missingRequired.length === 0) && (mode === "CREATE" || Boolean(matchField && mappedKeys.includes(matchField)));
  const serverViolations = violationMap(startError);

  const chooseModule = (key: string) => {
    setModuleKey(key);
    setFile(null);
    setPreview(null);
    setMapping({});
    setLastMapping(null);
    setJobId(null);
    setMode("CREATE");
    setMatchField("");
    router.replace(`?module=${encodeURIComponent(key)}`, { scroll: false });
  };

  const handleFile = async (candidate: File | undefined) => {
    if (!candidate || !activeModule) return;
    setUploadError(null);
    if (!/\.(xlsx|csv)$/i.test(candidate.name)) {
      setUploadError("Choose an Excel (.xlsx) or CSV file.");
      return;
    }
    if (candidate.size > MAX_BYTES) {
      setUploadError(`The file is larger than ${formatBytes(MAX_BYTES)}.`);
      return;
    }
    setFile(candidate);
    try {
      const result = await previewMutation.mutateAsync(candidate);
      setPreview(result);
      setMapping(suggested(result, lastMapping));
      setStartError(null);
      const firstUnique = result.fields.find((field) => field.unique && Object.values(result.suggestedMapping).includes(field.key));
      setMatchField((current) => current || firstUnique?.key || "");
    } catch (error) {
      setPreview(null);
      setUploadError(errorMessage(error));
    }
  };

  const startJob = async (dry: boolean) => {
    if (!file || !activeModule) return;
    setStartError(null);
    try {
      const job = await start.mutateAsync({
        file,
        options: { mode, matchField: mode === "UPSERT" ? matchField : undefined, dryRun: dry, mapping },
      });
      setLastMapping(mapping);
      setJobId(job.id);
      setStep("run");
    } catch (error) {
      if (error instanceof ApiError) {
        setStartError(error);
        if (error.isValidation) setStep("map");
        else toast.error(dry ? "Couldn't start the dry run" : "Couldn't start the import", { description: error.friendlyMessage });
      }
    }
  };

  const fixFile = () => {
    setLastMapping(mapping);
    setFile(null);
    setPreview(null);
    setJobId(null);
    setStep("upload");
  };

  const template = async (format: ExchangeFormat) => {
    if (!activeModule) return;
    setTemplatePending(format);
    try {
      await downloadImportTemplate(orgId, activeModule, format);
    } catch (error) {
      toast.error("Couldn't download the template", { description: errorMessage(error) });
    } finally {
      setTemplatePending(null);
    }
  };

  const reachable = (target: Step) => {
    switch (target) {
      case "module":
        return effectiveStep !== "run" || Boolean(jobId);
      case "upload":
        return Boolean(activeModule);
      case "map":
        return Boolean(preview) && effectiveStep !== "run";
      case "review":
        return Boolean(preview) && mappingValid && effectiveStep !== "run";
      case "run":
        return Boolean(jobId);
    }
  };

  if (loading) return <Skeleton className="mx-8 mt-6 h-72 rounded-lg" />;
  if (importable.length === 0) {
    return (
      <EmptyState icon={Lock} title="Nothing you can import" description="Importing needs permission to create records in a module. Ask your placement officer." />
    );
  }

  return (
    <>
      <PageHeader title="Data import" description="Bring students, companies and other records in from Excel or CSV, with a dry run and an error report.">
        <Stepper step={effectiveStep} reachable={reachable} onStep={setStep} />
      </PageHeader>
      <PageBody className="flex max-w-5xl flex-col gap-8">
        {effectiveStep === "module" ? (
          <StepCard title="What are you importing?" description="Only modules you can add records to are listed.">
            <RadioGroup
              value={activeModule ?? ""}
              onValueChange={chooseModule}
              className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3"
              aria-label="Module"
            >
              {importable.map((module) => (
                <Label
                  key={module.apiKey}
                  htmlFor={`module-${module.apiKey}`}
                  className={cn(
                    "flex cursor-pointer items-center gap-3 rounded-lg border border-border px-3.5 py-3 font-normal transition-colors hover:border-border-strong hover:bg-surface",
                    activeModule === module.apiKey && "border-primary/50 bg-primary-soft/40"
                  )}
                >
                  <RadioGroupItem id={`module-${module.apiKey}`} value={module.apiKey} />
                  <ModuleGlyph moduleKey={module.apiKey} className="size-4 text-muted-foreground" />
                  <span className="text-[13px] font-medium">{module.labelPlural}</span>
                </Label>
              ))}
            </RadioGroup>
            {activeModule ? (
              <div className="flex justify-end">
                <Button onClick={() => setStep("upload")}>
                  Continue <ArrowRight />
                </Button>
              </div>
            ) : null}
          </StepCard>
        ) : null}

        {effectiveStep === "upload" && definition ? (
          <StepCard
            title={`Upload ${definition.labelPlural.toLowerCase()}`}
            description="Start from the template so the columns match. The first worksheet is read; up to 50,000 rows and 25 MB."
            footer={
              <>
                <Button variant="outline" onClick={() => setStep("module")}>
                  <ArrowLeft /> Change module
                </Button>
                <Button disabled={!preview} onClick={() => setStep("map")}>
                  Map columns <ArrowRight />
                </Button>
              </>
            }
          >
            <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-surface px-4 py-3">
              <ModuleGlyph moduleKey={definition.apiKey} className="size-4 text-muted-foreground" />
              <span className="mr-auto text-[13px]">
                Template for <span className="font-medium">{definition.labelPlural}</span>, with drop-downs and a field guide
              </span>
              <Button variant="outline" size="sm" disabled={templatePending !== null} onClick={() => void template("xlsx")}>
                {templatePending === "xlsx" ? <Spinner /> : <FileSpreadsheet />} Excel template
              </Button>
              <Button variant="outline" size="sm" disabled={templatePending !== null} onClick={() => void template("csv")}>
                {templatePending === "csv" ? <Spinner /> : <FileText />} CSV
              </Button>
            </div>

            <input
              ref={inputRef}
              type="file"
              accept={ACCEPT}
              className="sr-only"
              aria-label="Import file"
              onChange={(event) => {
                void handleFile(event.target.files?.[0]);
                event.target.value = "";
              }}
            />
            {file && (previewMutation.isPending || preview) ? (
              <div className="flex items-center gap-3 rounded-lg border border-border px-4 py-3">
                <FileSpreadsheet className="size-5 shrink-0 text-success" />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[13px] font-medium">{file.name}</p>
                  <p className="text-xs text-muted-foreground">
                    {formatBytes(file.size)}
                    {preview ? ` · ${formatInteger(preview.totalRows)} rows · ${preview.headers.length} columns` : " · reading…"}
                  </p>
                </div>
                {previewMutation.isPending ? <Spinner /> : null}
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label="Remove file"
                  onClick={() => {
                    setFile(null);
                    setPreview(null);
                  }}
                >
                  <X />
                </Button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => inputRef.current?.click()}
                onDragOver={(event) => {
                  event.preventDefault();
                  setDragging(true);
                }}
                onDragLeave={() => setDragging(false)}
                onDrop={(event) => {
                  event.preventDefault();
                  setDragging(false);
                  void handleFile(event.dataTransfer.files?.[0]);
                }}
                className={cn(
                  "focus-ring flex flex-col items-center gap-2 rounded-lg border border-dashed border-border-strong px-4 py-10 text-center transition-colors hover:bg-surface",
                  dragging && "border-primary bg-primary-soft/40"
                )}
              >
                <UploadCloud className="size-7 text-muted-foreground" />
                <span className="text-sm font-medium">Drop your file here or click to browse</span>
                <span className="text-xs text-muted-foreground">Excel (.xlsx) or CSV (UTF-8; comma, semicolon or tab)</span>
              </button>
            )}
            {uploadError ? (
              <p role="alert" className="text-xs text-destructive">
                {uploadError}
              </p>
            ) : null}

            {preview ? (
              <div className="flex flex-col gap-2">
                <h3 className="text-[13px] font-semibold">
                  Preview <span className="font-normal text-muted-foreground">(first {Math.min(preview.rows.length, 8)} of {formatInteger(preview.totalRows)} rows)</span>
                </h3>
                <div className="overflow-auto rounded-lg border border-border">
                  <Table aria-label="File preview">
                    <TableHeader>
                      <tr>
                        {preview.headers.map((header) => (
                          <TableHead key={header}>{header}</TableHead>
                        ))}
                      </tr>
                    </TableHeader>
                    <TableBody>
                      {preview.rows.slice(0, 8).map((row, index) => (
                        <TableRow key={index} className="hover:bg-transparent">
                          {preview.headers.map((header, column) => (
                            <TableCell key={header} className="h-8 max-w-48 truncate whitespace-nowrap text-xs">
                              {row[column] || <span className="text-subtle-foreground">–</span>}
                            </TableCell>
                          ))}
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              </div>
            ) : null}
          </StepCard>
        ) : null}

        {effectiveStep === "map" && preview && definition ? (
          <StepCard
            title="Map columns to fields"
            description="Suggestions are filled in from the headers. Columns set to “Ignore” are skipped."
            footer={
              <>
                <Button variant="outline" onClick={() => setStep("upload")}>
                  <ArrowLeft /> Back
                </Button>
                <Button disabled={!mappingValid} onClick={() => setStep("review")}>
                  Review <ArrowRight />
                </Button>
              </>
            }
          >
            <div className="flex flex-col gap-2">
              <Label>How should rows be written?</Label>
              <RadioGroup value={mode} onValueChange={(value) => setMode(value === "UPSERT" ? "UPSERT" : "CREATE")} className="grid gap-2 sm:grid-cols-2">
                {(
                  [
                    ["CREATE", "Create new records", "Every row becomes a new record."],
                    ["UPSERT", "Create or update", "Rows matching an existing record by a unique field update it; others are created."],
                  ] as const
                ).map(([value, title, hint]) => (
                  <Label
                    key={value}
                    htmlFor={`mode-${value}`}
                    className={cn(
                      "flex cursor-pointer items-start gap-3 rounded-lg border border-border px-3.5 py-3 font-normal transition-colors hover:bg-surface",
                      mode === value && "border-primary/50 bg-primary-soft/40"
                    )}
                  >
                    <RadioGroupItem id={`mode-${value}`} value={value} className="mt-0.5" />
                    <span>
                      <span className="block text-[13px] font-medium">{title}</span>
                      <span className="block text-xs text-muted-foreground">{hint}</span>
                    </span>
                  </Label>
                ))}
              </RadioGroup>
              {mode === "UPSERT" ? (
                <div className="mt-2 flex flex-col gap-1.5 sm:w-80">
                  <Label htmlFor="match-field">Match existing records by</Label>
                  <Select value={matchField} onValueChange={setMatchField}>
                    <SelectTrigger id="match-field" aria-invalid={Boolean(serverViolations.get("matchField")) || (mode === "UPSERT" && !matchField) || undefined}>
                      <SelectValue placeholder="Choose a unique field" />
                    </SelectTrigger>
                    <SelectContent>
                      {fields
                        .filter((field) => field.unique)
                        .map((field) => (
                          <SelectItem key={field.key} value={field.key} disabled={!mappedKeys.includes(field.key)}>
                            {field.label}
                            {!mappedKeys.includes(field.key) ? " (not mapped)" : ""}
                          </SelectItem>
                        ))}
                    </SelectContent>
                  </Select>
                  <p className="text-xs text-muted-foreground">
                    {uniqueMapped.length === 0 ? "Map a unique column (such as roll number or email) to update existing records." : "The match column itself is never changed."}
                  </p>
                  {serverViolations.get("matchField")?.map((message) => (
                    <p key={message} className="text-xs text-destructive">
                      {message}
                    </p>
                  ))}
                </div>
              ) : null}
            </div>

            <div className="overflow-hidden rounded-lg border border-border">
              <Table aria-label="Column mapping">
                <TableHeader>
                  <tr>
                    <TableHead>Column in your file</TableHead>
                    <TableHead className="hidden md:table-cell">Sample</TableHead>
                    <TableHead className="w-72">Field</TableHead>
                  </tr>
                </TableHeader>
                <TableBody>
                  {preview.headers.map((header, column) => {
                    const value = mapping[header] ?? null;
                    const problems = serverViolations.get(`mapping.${header}`) ?? [];
                    const duplicate = value !== null && duplicates.has(value);
                    return (
                      <TableRow key={header} className="hover:bg-transparent">
                        <TableCell className="h-12 font-medium">{header}</TableCell>
                        <TableCell className="hidden h-12 max-w-56 truncate text-xs text-muted-foreground md:table-cell">
                          {preview.rows
                            .slice(0, 3)
                            .map((row) => row[column])
                            .filter(Boolean)
                            .join(" · ") || "–"}
                        </TableCell>
                        <TableCell className="h-12 py-1.5">
                          <Select value={value ?? IGNORE} onValueChange={(next) => setMapping((current) => ({ ...current, [header]: next === IGNORE ? null : next }))}>
                            <SelectTrigger size="sm" aria-label={`Field for column ${header}`} aria-invalid={duplicate || problems.length > 0 || undefined}>
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value={IGNORE}>
                                <span className="text-muted-foreground">Ignore this column</span>
                              </SelectItem>
                              <SelectSeparator />
                              {fields.map((field) => (
                                <SelectItem key={field.key} value={field.key}>
                                  {field.label}
                                  {field.required ? " *" : ""}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                          {duplicate ? <p className="mt-1 text-xs text-destructive">Mapped to more than one column</p> : null}
                          {problems.map((message) => (
                            <p key={message} className="mt-1 text-xs text-destructive">
                              {message}
                            </p>
                          ))}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </div>

            <div className="flex flex-col gap-2">
              <h3 className="text-[13px] font-semibold">Required fields</h3>
              <ul className="flex flex-wrap gap-1.5">
                {fields
                  .filter((field) => field.required)
                  .map((field) => {
                    const mapped = mappedKeys.includes(field.key);
                    return (
                      <li key={field.key}>
                        <Badge variant={mapped ? "success" : mode === "UPSERT" ? "outline" : "destructive"}>
                          {mapped ? <Check /> : <CircleDashed />} {field.label}
                        </Badge>
                      </li>
                    );
                  })}
              </ul>
              {missingRequired.length > 0 && mode === "CREATE" ? (
                <p className="text-xs text-destructive">Map every required field to create records.</p>
              ) : missingRequired.length > 0 ? (
                <p className="text-xs text-muted-foreground">Updates only touch mapped columns; new rows still need every required field.</p>
              ) : null}
            </div>
          </StepCard>
        ) : null}

        {effectiveStep === "review" && preview && definition && file ? (
          <StepCard
            title="Review and start"
            description="A dry run writes nothing: it reports exactly what a real import would create, update and reject."
            footer={
              <>
                <Button variant="outline" onClick={() => setStep("map")}>
                  <ArrowLeft /> Back
                </Button>
                <Button disabled={start.isPending || !mappingValid} onClick={() => void startJob(dryRun)}>
                  {start.isPending ? <Spinner className="text-primary-foreground" /> : dryRun ? <FlaskConical /> : <UploadCloud />}
                  {dryRun ? "Start dry run" : `Import ${formatInteger(preview.totalRows)} rows`}
                </Button>
              </>
            }
          >
            <dl className="grid gap-3 text-[13px] sm:grid-cols-2">
              <div className="rounded-lg border border-border px-4 py-3">
                <dt className="text-xs text-muted-foreground">Module</dt>
                <dd className="font-medium">{definition.labelPlural}</dd>
              </div>
              <div className="rounded-lg border border-border px-4 py-3">
                <dt className="text-xs text-muted-foreground">File</dt>
                <dd className="truncate font-medium">
                  {file.name} · {formatInteger(preview.totalRows)} rows
                </dd>
              </div>
              <div className="rounded-lg border border-border px-4 py-3">
                <dt className="text-xs text-muted-foreground">Mode</dt>
                <dd className="font-medium">
                  {mode === "UPSERT" ? `Create or update, matched by ${fields.find((field) => field.key === matchField)?.label ?? matchField}` : "Create new records"}
                </dd>
              </div>
              <div className="rounded-lg border border-border px-4 py-3">
                <dt className="text-xs text-muted-foreground">Columns</dt>
                <dd className="font-medium">
                  {mappedKeys.length} mapped, {preview.headers.length - mappedKeys.length} ignored
                </dd>
              </div>
            </dl>
            <div className="flex items-start justify-between gap-4 rounded-lg border border-border px-4 py-3">
              <div>
                <Label htmlFor="dry-run">Dry run first</Label>
                <p className="text-xs text-muted-foreground">Recommended. You can run the real import right after with one click.</p>
              </div>
              <Switch id="dry-run" checked={dryRun} onCheckedChange={setDryRun} />
            </div>
            {startError && !startError.isValidation ? (
              <p role="alert" className="text-xs text-destructive">
                {startError.friendlyMessage}
              </p>
            ) : null}
          </StepCard>
        ) : null}

        {effectiveStep === "run" && jobId && activeModule ? (
          <StepCard
            title="Import progress"
            description="You can leave this page; the import keeps running and shows up under Recent imports."
            footer={
              <>
                <Button
                  variant="outline"
                  onClick={() => {
                    fixFile();
                    setLastMapping(null);
                  }}
                >
                  <RotateCcw /> Start another import
                </Button>
              </>
            }
          >
            <JobProgress
              orgId={orgId}
              moduleKey={activeModule}
              jobId={jobId}
              onFixFile={fixFile}
              onRealRun={file ? () => void startJob(false) : null}
              starting={start.isPending}
            />
          </StepCard>
        ) : null}

        {activeModule ? (
          <JobHistory
            orgId={orgId}
            moduleKey={activeModule}
            activeJobId={jobId}
            onOpen={(job) => {
              setJobId(job.id);
              setStep("run");
            }}
          />
        ) : null}
      </PageBody>
    </>
  );
}
