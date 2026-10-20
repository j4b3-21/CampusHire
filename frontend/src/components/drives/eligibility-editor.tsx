"use client";

import * as React from "react";
import { toast } from "sonner";
import { AlertTriangle, Check, CloudUpload, RotateCcw, Users } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { MeterBar } from "@/components/charts/chart-kit";
import { ErrorState } from "@/components/common/error-state";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { MultiCombobox, type MultiComboboxOption } from "@/components/ui/multi-combobox";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ApiError, useEligibilityPreview, usePatchRecord, useRecords, type VersionedRecord } from "@/lib/api";
import { formatLpa, reasonLabel } from "@/lib/campushire";
import {
  draftFromRules,
  parseRules,
  rulesFromDraft,
  rulesKey,
  rulesToJson,
  serverRuleErrors,
  type RulesDraft,
  type RulesDraftErrors,
} from "@/lib/eligibility";
import { recordTitle, selectOptions } from "@/lib/metadata/fields";
import { formatInteger } from "@/lib/metadata/format";
import { cn } from "@/lib/utils";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import type { JsonValue } from "@/types/api";

const AUTOSAVE_MS = 800;
const PREVIEW_DEBOUNCE_MS = 400;

function useRecordOptions(orgId: string, moduleKey: string): { options: MultiComboboxOption[]; names: Map<string, string> } {
  const { moduleByKey } = useOrg();
  const definition = moduleByKey(moduleKey);
  const records = useRecords(orgId, moduleKey, { size: 100 });
  return React.useMemo(() => {
    const options = (records.data?.items ?? []).map((record) => ({ value: record.id, label: recordTitle(definition, record) }));
    return { options, names: new Map(options.map((option) => [option.value, option.label])) };
  }, [records.data, definition]);
}

function NumberRule({
  id,
  label,
  hint,
  value,
  onChange,
  error,
  disabled,
  step,
  suffix,
}: {
  id: string;
  label: string;
  hint: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
  disabled?: boolean;
  step?: string;
  suffix?: string;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <div className="relative">
        <Input
          id={id}
          inputMode="decimal"
          type="number"
          step={step}
          min={0}
          value={value}
          placeholder="Any"
          disabled={disabled}
          onChange={(event) => onChange(event.target.value)}
          aria-invalid={Boolean(error) || undefined}
          aria-describedby={`${id}-hint`}
          className={cn("tabular", suffix && "pr-8")}
        />
        {suffix ? <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">{suffix}</span> : null}
      </div>
      <p id={`${id}-hint`} className={cn("text-xs", error ? "text-destructive" : "text-muted-foreground")}>
        {error ?? hint}
      </p>
    </div>
  );
}

function SaveState({ state }: { state: "saved" | "dirty" | "saving" | "invalid" | "conflict" }) {
  const content = {
    saved: { icon: <Check className="size-3.5 text-success" />, text: "Saved" },
    dirty: { icon: <CloudUpload className="size-3.5" />, text: "Unsaved changes" },
    saving: { icon: <Spinner className="size-3.5" />, text: "Saving…" },
    invalid: { icon: <AlertTriangle className="size-3.5 text-destructive" />, text: "Fix the highlighted rules" },
    conflict: { icon: <AlertTriangle className="size-3.5 text-warning" />, text: "Changed elsewhere" },
  }[state];
  return (
    <span role="status" className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
      {content.icon}
      {content.text}
    </span>
  );
}

function PreviewPanel({
  driveId,
  record,
  draft,
  stale,
}: {
  driveId: string;
  record: VersionedRecord;
  /** Unsaved rules to preview; `null` previews the saved rules. */
  draft: JsonValue | null;
  stale: boolean;
}) {
  const { orgId } = useOrg();
  const preview = useEligibilityPreview(orgId, driveId, record.version, { sample: 10, draft });
  const departments = useRecordOptions(orgId, "department");
  const data = preview.data;
  const reasons = Object.entries(data?.reasons ?? {})
    .filter(([, count]) => (count ?? 0) > 0)
    .sort((a, b) => (b[1] ?? 0) - (a[1] ?? 0));
  const maxReason = Math.max(1, ...reasons.map(([, count]) => count ?? 0));
  const invalid = preview.error instanceof ApiError && preview.error.status === 400;

  return (
    <section aria-labelledby="preview-heading" aria-busy={preview.isFetching || undefined} className="flex min-w-0 flex-col gap-4 rounded-lg border border-border bg-card p-4">
      <header className="flex items-center gap-2">
        <h3 id="preview-heading" className="text-[13px] font-semibold">
          Eligibility preview
        </h3>
        {preview.isFetching ? <Spinner className="size-3.5" label="Updating preview" /> : null}
        {data?.driveCtc ? <span className="ml-auto text-xs text-muted-foreground">Drive CTC {formatLpa(data.driveCtc)}</span> : null}
      </header>
      {stale ? (
        <p className="rounded-md bg-warning-soft px-3 py-2 text-xs text-warning-soft-foreground">Fix the highlighted rules to update the preview.</p>
      ) : null}
      {preview.isLoading ? (
        <div className="flex flex-col gap-3">
          <Skeleton className="h-12 w-40" />
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-32 w-full" />
        </div>
      ) : invalid ? (
        <div role="alert" className="rounded-md border border-destructive/20 bg-destructive-soft px-3 py-2.5 text-[13px] text-destructive-soft-foreground">
          <p className="font-medium">{draft === null ? "The saved rules are invalid" : "These rules are invalid"}</p>
          <ul className="mt-1 list-disc pl-5 text-xs">
            {(preview.error as ApiError).errors.map((violation) => (
              <li key={violation.field + violation.message}>
                {violation.field}: {violation.message}
              </li>
            ))}
          </ul>
        </div>
      ) : preview.isError ? (
        <ErrorState size="sm" error={preview.error} onRetry={() => void preview.refetch()} />
      ) : data ? (
        <>
          <div className="flex items-end gap-3">
            <span className="grid size-10 place-items-center rounded-lg bg-primary-soft text-primary-soft-foreground">
              <Users className="size-5" />
            </span>
            <div>
              <p className="text-2xl font-semibold tracking-[-0.03em] tabular">
                {formatInteger(data.eligibleCount)}
                <span className="text-sm font-normal text-muted-foreground"> of {formatInteger(data.totalConsidered)} students</span>
              </p>
              <p className="text-xs text-muted-foreground">are eligible right now</p>
            </div>
          </div>
          <MeterBar value={data.eligibleCount} max={Math.max(1, data.totalConsidered)} />

          {data.breakdown.byDepartment.length > 0 ? (
            <div className="flex flex-col gap-2">
              <h4 className="text-xs font-medium text-muted-foreground">By department</h4>
              <ul className="flex flex-col gap-2">
                {data.breakdown.byDepartment.map((row) => (
                  <li key={row.departmentId ?? "none"} className="grid grid-cols-[minmax(0,1fr)_6rem_4.5rem] items-center gap-3 text-[13px]">
                    <span className="truncate">{row.departmentId ? departments.names.get(row.departmentId) ?? "Department" : "No department"}</span>
                    <MeterBar value={row.eligible} max={Math.max(1, row.considered)} />
                    <span className="text-right text-xs text-muted-foreground tabular">
                      <span className="font-medium text-foreground">{row.eligible}</span>/{row.considered}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          <div className="flex flex-col gap-2">
            <h4 className="text-xs font-medium text-muted-foreground">Why students are excluded</h4>
            {reasons.length === 0 ? (
              <p className="text-[13px] text-muted-foreground">Nobody is excluded by these rules.</p>
            ) : (
              <ul className="flex flex-col gap-2">
                {reasons.map(([code, count]) => (
                  <li key={code} className="grid grid-cols-[minmax(0,1fr)_6rem_3rem] items-center gap-3 text-[13px]">
                    <span className="truncate">{reasonLabel(code)}</span>
                    <MeterBar value={count ?? 0} max={maxReason} color="var(--chart-4)" />
                    <span className="text-right text-xs tabular">{formatInteger(count ?? 0)}</span>
                  </li>
                ))}
              </ul>
            )}
            <p className="text-[11px] text-subtle-foreground">A student can fail several rules.</p>
          </div>

          {data.sample.length > 0 ? (
            <div className="flex flex-col gap-2">
              <h4 className="text-xs font-medium text-muted-foreground">Sample of eligible students</h4>
              <div className="overflow-hidden rounded-md border border-border">
                <Table aria-label="Sample of eligible students">
                  <TableHeader>
                    <tr>
                      <TableHead>Roll no.</TableHead>
                      <TableHead>Name</TableHead>
                      <TableHead className="hidden sm:table-cell">Department</TableHead>
                      <TableHead className="text-right">CGPA</TableHead>
                    </tr>
                  </TableHeader>
                  <TableBody>
                    {data.sample.map((student) => (
                      <TableRow key={student.studentId} className="hover:bg-transparent">
                        <TableCell className="h-8 font-mono text-xs">{student.rollNumber}</TableCell>
                        <TableCell className="h-8 max-w-40 truncate">{student.fullName}</TableCell>
                        <TableCell className="hidden h-8 max-w-40 truncate text-muted-foreground sm:table-cell">
                          {student.departmentId ? departments.names.get(student.departmentId) ?? "–" : "–"}
                        </TableCell>
                        <TableCell className="h-8 text-right tabular">{student.cgpa ?? "–"}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            </div>
          ) : null}
        </>
      ) : null}
    </section>
  );
}

/**
 * Edits the drive's `eligibility` JSON field (saved with a record PATCH) next to a live preview. Draft drives save
 * automatically shortly after each change; published drives save on demand because students are affected at once.
 */
export function EligibilityEditor({ record, canEdit }: { record: VersionedRecord; canEdit: boolean }) {
  const { orgId, moduleByKey } = useOrg();
  const patch = usePatchRecord(orgId, "drive");
  const departments = useRecordOptions(orgId, "department");
  const batches = useRecordOptions(orgId, "batch");
  const programField = moduleByKey("student")?.fields.find((field) => field.apiKey === "program");
  const programs = React.useMemo(
    () => (programField ? selectOptions(programField).map((option) => ({ value: option.value, label: option.label })) : []),
    [programField]
  );

  const savedRules = React.useMemo(() => parseRules(record.data.eligibility), [record.data.eligibility]);
  const savedKey = rulesKey(savedRules);
  const [draft, setDraft] = React.useState<RulesDraft>(() => draftFromRules(savedRules));
  const [serverErrors, setServerErrors] = React.useState<RulesDraftErrors>({});
  const [conflict, setConflict] = React.useState(false);
  const [lastSavedKey, setLastSavedKey] = React.useState(savedKey);

  const { rules, errors: clientErrors } = React.useMemo(() => rulesFromDraft(draft), [draft]);
  const draftKey = rulesKey(rules);
  const { mutateAsync, isPending: saving } = patch;
  const errors = { ...serverErrors, ...clientErrors };
  const valid = Object.keys(clientErrors).length === 0;
  const dirty = draftKey !== savedKey;
  const status = (typeof record.data.drive_status === "string" ? record.data.drive_status : "draft") as string;
  const autosave = canEdit && status === "draft";

  // Adopt saved rules that changed elsewhere while there are no local edits.
  if (savedKey !== lastSavedKey) {
    setLastSavedKey(savedKey);
    if (draftKey === lastSavedKey) setDraft(draftFromRules(savedRules));
  }

  const update = (patchDraft: Partial<RulesDraft>) => {
    setServerErrors({});
    setConflict(false);
    setDraft((current) => ({ ...current, ...patchDraft }));
  };

  const save = React.useCallback(async () => {
    if (!valid || !dirty || saving) return;
    try {
      await mutateAsync({ id: record.id, ifMatch: record.etag, data: { eligibility: rulesToJson(rules) } });
      if (!autosave) toast.success("Eligibility rules saved");
    } catch (error) {
      if (error instanceof ApiError && error.isValidation) setServerErrors(serverRuleErrors(error.errors));
      else if (error instanceof ApiError && error.isVersionConflict) setConflict(true);
    }
  }, [valid, dirty, saving, mutateAsync, record.id, record.etag, rules, autosave]);

  React.useEffect(() => {
    if (!autosave || !dirty || !valid || conflict || Object.keys(serverErrors).length > 0) return;
    const timer = setTimeout(() => void save(), AUTOSAVE_MS);
    return () => clearTimeout(timer);
  }, [autosave, dirty, valid, conflict, serverErrors, save]);

  const draftJson = React.useMemo(
    () => (dirty && valid ? JSON.stringify(rulesToJson(rules) ?? {}) : null),
    [dirty, valid, rules]
  );
  const previewDraftJson = useDebouncedValue(draftJson, PREVIEW_DEBOUNCE_MS);
  const previewDraft = React.useMemo<JsonValue | null>(
    () => (previewDraftJson === null ? null : (JSON.parse(previewDraftJson) as JsonValue)),
    [previewDraftJson]
  );

  const saveState = conflict ? "conflict" : !valid ? "invalid" : saving ? "saving" : dirty ? "dirty" : "saved";
  const disabled = !canEdit;

  return (
    <div className="grid items-start gap-6 2xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      <section aria-labelledby="rules-heading" className="flex min-w-0 flex-col gap-5 rounded-lg border border-border bg-card p-4">
        <header className="flex flex-wrap items-center gap-2">
          <div className="min-w-0 flex-1">
            <h3 id="rules-heading" className="text-[13px] font-semibold">
              Eligibility rules
            </h3>
            <p className="text-xs text-muted-foreground">Empty rules don&apos;t restrict anyone.{autosave ? " Changes save automatically while the drive is a draft." : ""}</p>
          </div>
          {canEdit ? <SaveState state={saveState} /> : null}
        </header>

        <div className="grid gap-4 sm:grid-cols-2">
          <NumberRule id="rule-cgpa" label="Minimum CGPA" hint="Out of 10" step="0.01" value={draft.minCgpa} onChange={(minCgpa) => update({ minCgpa })} error={errors.minCgpa} disabled={disabled} />
          <NumberRule
            id="rule-backlogs"
            label="Maximum active backlogs"
            hint="0 means no active backlogs"
            step="1"
            value={draft.maxActiveBacklogs}
            onChange={(maxActiveBacklogs) => update({ maxActiveBacklogs })}
            error={errors.maxActiveBacklogs}
            disabled={disabled}
          />
          <NumberRule id="rule-tenth" label="Minimum 10th %" hint="Class 10 percentage" step="0.1" suffix="%" value={draft.minTenth} onChange={(minTenth) => update({ minTenth })} error={errors.minTenth} disabled={disabled} />
          <NumberRule id="rule-twelfth" label="Minimum 12th %" hint="Class 12 percentage" step="0.1" suffix="%" value={draft.minTwelfth} onChange={(minTwelfth) => update({ minTwelfth })} error={errors.minTwelfth} disabled={disabled} />
        </div>

        {(
          [
            ["departments", "Departments", departments.options, "All departments"],
            ["batches", "Batches", batches.options, "All batches"],
            ["programs", "Programs", programs, "All programs"],
          ] as const
        ).map(([key, label, options, placeholder]) => (
          <div key={key} className="flex flex-col gap-1.5">
            <Label htmlFor={`rule-${key}`}>{label}</Label>
            <MultiCombobox
              id={`rule-${key}`}
              value={draft[key]}
              onChange={(value) => update({ [key]: value })}
              options={options}
              placeholder={placeholder}
              disabled={disabled}
              invalid={Boolean(errors[key])}
              aria-label={label}
            />
            {errors[key] ? <p className="text-xs text-destructive">{errors[key]}</p> : null}
          </div>
        ))}

        <div className="flex items-start justify-between gap-4 rounded-md border border-border px-3 py-2.5">
          <div>
            <Label htmlFor="rule-exclude-placed">Exclude placed students</Label>
            <p className="text-xs text-muted-foreground">Students holding an active offer can&apos;t apply (dream-offer policy still applies).</p>
          </div>
          <Switch id="rule-exclude-placed" checked={draft.excludePlaced} onCheckedChange={(checked) => update({ excludePlaced: checked })} disabled={disabled} />
        </div>

        {conflict ? (
          <div role="alert" className="flex flex-wrap items-center gap-2 rounded-md border border-warning/30 bg-warning-soft px-3 py-2 text-xs text-warning-soft-foreground">
            Someone else changed this drive. Reload the page to get the latest version; your edits are kept until then.
          </div>
        ) : null}

        {canEdit && !autosave ? (
          <div className="flex items-center justify-end gap-2 border-t border-border pt-4">
            <Button variant="outline" size="sm" disabled={!dirty || saving} onClick={() => update(draftFromRules(savedRules))}>
              <RotateCcw /> Discard
            </Button>
            <Button size="sm" disabled={!dirty || !valid || saving} onClick={() => void save()}>
              {saving ? <Spinner className="text-primary-foreground" /> : null}
              Save rules
            </Button>
          </div>
        ) : null}
        {!canEdit ? <p className="text-xs text-muted-foreground">Only placement officers can change eligibility rules.</p> : null}
      </section>
      <PreviewPanel driveId={record.id} record={record} draft={previewDraft} stale={dirty && !valid} />
    </div>
  );
}
