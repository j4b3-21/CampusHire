"use client";

import * as React from "react";
import Link from "next/link";
import { toast } from "sonner";
import { ChartColumn, ChevronDown, Download, FileSpreadsheet, FileText, Lock, RotateCcw } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { MeterBar, SimpleBarChart, SimpleLineChart } from "@/components/charts/chart-kit";
import { KpiGrid, Panel } from "@/components/dashboard/dashboard-kit";
import { ErrorState } from "@/components/common/error-state";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { LookupInput } from "@/components/records/inputs/lookup-input";
import { Button } from "@/components/ui/button";
import { Combobox } from "@/components/ui/combobox";
import { DatePicker } from "@/components/ui/date-picker";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { EmptyState } from "@/components/ui/empty-state";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import {
  downloadPlacementReport,
  errorMessage,
  useAnalyticsCompanies,
  useAnalyticsDepartments,
  useAnalyticsOverview,
  useAnalyticsTrend,
  useCtcDistribution,
  useDriveFunnel,
  useRecords,
} from "@/lib/api";
import { formatLpa, formatRate, stageLabel } from "@/lib/campushire";
import { recordTitle } from "@/lib/metadata/fields";
import { formatDateTime, formatInteger } from "@/lib/metadata/format";
import { orgPath, recordPath } from "@/lib/navigation";
import { cn } from "@/lib/utils";
import type { AnalyticsFilters, ReportFormat } from "@/types/analytics";

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

function formatPeriod(period: string) {
  const [year, month] = period.split("-");
  const index = Number(month) - 1;
  return index >= 0 && index < 12 ? `${MONTHS[index]} ${year.slice(2)}` : period;
}

function useOptions(orgId: string, moduleKey: string, enabled: boolean) {
  const { moduleByKey } = useOrg();
  const records = useRecords(orgId, moduleKey, { size: 100 }, { enabled });
  const definition = moduleByKey(moduleKey);
  return React.useMemo(
    () => (records.data?.items ?? []).map((record) => ({ value: record.id, label: recordTitle(definition, record) })),
    [records.data, definition]
  );
}

function Filters({ value, onChange }: { value: AnalyticsFilters; onChange: (next: AnalyticsFilters) => void }) {
  const { orgId, capabilities } = useOrg();
  const departments = useOptions(orgId, "department", capabilities("department").read);
  const batches = useOptions(orgId, "batch", capabilities("batch").read);
  const invalidRange = Boolean(value.from && value.to && value.from > value.to);
  const active = Boolean(value.batchId || value.departmentId || value.from || value.to);

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap items-end gap-3" role="group" aria-label="Filters">
        <div className="flex w-full flex-col gap-1 sm:w-52">
          <Label htmlFor="filter-department" className="text-xs text-muted-foreground">
            Department
          </Label>
          <Combobox
            id="filter-department"
            value={value.departmentId ?? null}
            onChange={(departmentId) => onChange({ ...value, departmentId: departmentId ?? undefined })}
            options={departments}
            placeholder="All departments"
            searchPlaceholder="Search departments…"
          />
        </div>
        <div className="flex w-full flex-col gap-1 sm:w-44">
          <Label htmlFor="filter-batch" className="text-xs text-muted-foreground">
            Batch
          </Label>
          <Combobox
            id="filter-batch"
            value={value.batchId ?? null}
            onChange={(batchId) => onChange({ ...value, batchId: batchId ?? undefined })}
            options={batches}
            placeholder="All batches"
          />
        </div>
        <div className="flex w-[calc(50%-0.375rem)] flex-col gap-1 sm:w-40">
          <Label htmlFor="filter-from" className="text-xs text-muted-foreground">
            From
          </Label>
          <DatePicker id="filter-from" value={value.from ?? null} onChange={(from) => onChange({ ...value, from: from ?? undefined })} placeholder="Any time" invalid={invalidRange} />
        </div>
        <div className="flex w-[calc(50%-0.375rem)] flex-col gap-1 sm:w-40">
          <Label htmlFor="filter-to" className="text-xs text-muted-foreground">
            To
          </Label>
          <DatePicker id="filter-to" value={value.to ?? null} onChange={(to) => onChange({ ...value, to: to ?? undefined })} placeholder="Today" invalid={invalidRange} />
        </div>
        {active ? (
          <Button variant="subtle" size="sm" onClick={() => onChange({})}>
            <RotateCcw /> Reset
          </Button>
        ) : null}
      </div>
      {invalidRange ? (
        <p role="alert" className="text-xs text-destructive">
          “From” must be on or before “To”.
        </p>
      ) : (
        <p className="text-xs text-muted-foreground">Dates filter offers and applications by when they were created.</p>
      )}
    </div>
  );
}

function ReportButton({ orgId, filters, disabled }: { orgId: string; filters: AnalyticsFilters; disabled: boolean }) {
  const [pending, setPending] = React.useState<ReportFormat | null>(null);
  const download = async (format: ReportFormat) => {
    setPending(format);
    try {
      const name = await downloadPlacementReport(orgId, format, filters);
      toast.success("Report downloaded", { description: name });
    } catch (error) {
      toast.error("Couldn't download the report", { description: errorMessage(error) });
    } finally {
      setPending(null);
    }
  };
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button disabled={disabled || pending !== null}>
          {pending ? <Spinner className="text-primary-foreground" /> : <Download />}
          Placement report <ChevronDown className="opacity-70" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        <DropdownMenuLabel>With the current filters</DropdownMenuLabel>
        <DropdownMenuItem onSelect={() => void download("xlsx")}>
          <FileSpreadsheet /> Excel workbook (.xlsx)
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={() => void download("pdf")}>
          <FileText /> PDF summary
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function DepartmentsSection({ orgId, filters }: { orgId: string; filters: AnalyticsFilters }) {
  const departments = useAnalyticsDepartments(orgId, filters);
  const rows = departments.data?.departments ?? [];
  const chartData = rows.map((row) => ({
    department: row.departmentName ?? "No department",
    participating: row.participatingStudents,
    placed: row.placedStudents,
  }));
  return (
    <Panel title="Departments" description="Placement by department (students follow their current department)" bodyClassName="p-0">
      {departments.isLoading ? (
        <div className="p-4">
          <Skeleton className="h-48 w-full" />
        </div>
      ) : departments.isError ? (
        <ErrorState size="sm" error={departments.error} onRetry={() => void departments.refetch()} />
      ) : rows.length === 0 ? (
        <p className="px-4 py-10 text-center text-[13px] text-muted-foreground">No students match these filters.</p>
      ) : (
        <div className="grid gap-0 2xl:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
          <Table aria-label="Placement by department">
            <TableHeader>
              <tr>
                <TableHead>Department</TableHead>
                <TableHead className="text-right">Students</TableHead>
                <TableHead className="text-right">Placed</TableHead>
                <TableHead className="w-36">Rate</TableHead>
                <TableHead className="text-right">Offers</TableHead>
                <TableHead className="text-right">Median</TableHead>
                <TableHead className="text-right">Highest</TableHead>
              </tr>
            </TableHeader>
            <TableBody>
              {rows.map((row) => (
                <TableRow key={row.departmentId ?? "none"} className="hover:bg-transparent">
                  <TableCell className="h-10 max-w-48 truncate font-medium">{row.departmentName ?? "No department"}</TableCell>
                  <TableCell className="text-right tabular">
                    {formatInteger(row.participatingStudents)}
                    <span className="text-muted-foreground">/{formatInteger(row.totalStudents)}</span>
                  </TableCell>
                  <TableCell className="text-right tabular">{formatInteger(row.placedStudents)}</TableCell>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <MeterBar value={row.placementRate} max={1} className="w-16" />
                      <span className="tabular">{formatRate(row.placementRate)}</span>
                    </div>
                  </TableCell>
                  <TableCell className="text-right tabular">{formatInteger(row.offers)}</TableCell>
                  <TableCell className="whitespace-nowrap text-right tabular">{formatLpa(row.medianCtc)}</TableCell>
                  <TableCell className="whitespace-nowrap text-right tabular">{formatLpa(row.highestCtc)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <div className="border-t border-border p-4 2xl:border-l 2xl:border-t-0">
            <SimpleBarChart
              data={chartData}
              xKey="department"
              series={[
                { key: "participating", label: "Participating", color: "var(--chart-muted)" },
                { key: "placed", label: "Placed", color: "var(--chart-1)" },
              ]}
              layout="vertical"
              yWidth={120}
              height={Math.max(180, chartData.length * 52 + 40)}
              label={`Placed and participating students by department: ${chartData.map((row) => `${row.department} ${row.placed} of ${row.participating}`).join("; ")}`}
            />
          </div>
        </div>
      )}
    </Panel>
  );
}

function CompaniesSection({ orgId, filters }: { orgId: string; filters: AnalyticsFilters }) {
  const companies = useAnalyticsCompanies(orgId, filters, 10);
  const rows = companies.data?.companies ?? [];
  const maxOffers = Math.max(1, ...rows.map((row) => row.offers));
  return (
    <Panel title="Top companies" description="Ranked by counted offers" bodyClassName="p-0">
      {companies.isLoading ? (
        <div className="p-4">
          <Skeleton className="h-40 w-full" />
        </div>
      ) : companies.isError ? (
        <ErrorState size="sm" error={companies.error} onRetry={() => void companies.refetch()} />
      ) : rows.length === 0 ? (
        <p className="px-4 py-10 text-center text-[13px] text-muted-foreground">No offers yet for these filters.</p>
      ) : (
        <Table aria-label="Top companies">
          <TableHeader>
            <tr>
              <TableHead>Company</TableHead>
              <TableHead className="w-40">Offers</TableHead>
              <TableHead className="text-right">Drives</TableHead>
              <TableHead className="text-right">Highest</TableHead>
              <TableHead className="text-right">Average</TableHead>
            </tr>
          </TableHeader>
          <TableBody>
            {rows.map((row) => (
              <TableRow key={row.companyId} className="hover:bg-transparent">
                <TableCell className="h-10 max-w-48 truncate">
                  <Link href={recordPath(orgId, "company", row.companyId)} className="focus-ring rounded-sm font-medium hover:underline">
                    {row.companyName ?? "Company"}
                  </Link>
                </TableCell>
                <TableCell>
                  <div className="flex items-center gap-2">
                    <MeterBar value={row.offers} max={maxOffers} className="w-20" />
                    <span className="tabular">{formatInteger(row.offers)}</span>
                  </div>
                </TableCell>
                <TableCell className="text-right tabular">{formatInteger(row.drives)}</TableCell>
                <TableCell className="whitespace-nowrap text-right tabular">{formatLpa(row.highestCtc)}</TableCell>
                <TableCell className="whitespace-nowrap text-right tabular">{formatLpa(row.averageCtc)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Panel>
  );
}

function CtcSection({ orgId, filters }: { orgId: string; filters: AnalyticsFilters }) {
  const ctc = useCtcDistribution(orgId, filters);
  const data = (ctc.data?.buckets ?? []).map((bucket) => ({ bucket: `${bucket.label}`, offers: bucket.count }));
  return (
    <Panel title="CTC distribution" description={ctc.data ? `${formatInteger(ctc.data.offers)} counted offers, in lakh per annum` : "Counted offers, in lakh per annum"}>
      {ctc.isLoading ? (
        <Skeleton className="h-56 w-full" />
      ) : ctc.isError ? (
        <ErrorState size="sm" error={ctc.error} onRetry={() => void ctc.refetch()} />
      ) : (
        <SimpleBarChart
          data={data}
          xKey="bucket"
          series={[{ key: "offers", label: "Offers" }]}
          height={230}
          formatValue={(value) => `${value} ${value === 1 ? "offer" : "offers"}`}
          label={`CTC distribution in LPA: ${data.map((row) => `${row.bucket} ${row.offers}`).join(", ")}`}
        />
      )}
    </Panel>
  );
}

function TrendSection({ orgId, filters }: { orgId: string; filters: AnalyticsFilters }) {
  const trend = useAnalyticsTrend(orgId, filters);
  const data = (trend.data?.points ?? []).map((point) => ({ ...point }));
  return (
    <Panel title="Monthly trend" description="Offers and applications per month, last 12 months">
      {trend.isLoading ? (
        <Skeleton className="h-56 w-full" />
      ) : trend.isError ? (
        <ErrorState size="sm" error={trend.error} onRetry={() => void trend.refetch()} />
      ) : (
        <SimpleLineChart
          data={data}
          xKey="period"
          formatX={formatPeriod}
          height={240}
          series={[
            { key: "applications", label: "Applications", color: "var(--chart-2)" },
            { key: "offers", label: "Offers", color: "var(--chart-1)" },
          ]}
          label={`Monthly applications and offers: ${data.map((point) => `${formatPeriod(point.period)} ${point.applications} applications, ${point.offers} offers`).join("; ")}`}
        />
      )}
    </Panel>
  );
}

function FunnelSection({ orgId, filters }: { orgId: string; filters: AnalyticsFilters }) {
  const [driveId, setDriveId] = React.useState<string | null>(null);
  const funnel = useDriveFunnel(orgId, driveId, filters);
  const stages = funnel.data?.stages ?? [];
  const total = funnel.data?.applications ?? 0;
  return (
    <Panel
      title="Drive funnel"
      description="Applications of one drive by their current stage"
      actions={
        <div className="w-full max-w-64 sm:w-64">
          <LookupInput orgId={orgId} targetModuleKey="drive" value={driveId} onChange={setDriveId} placeholder="Choose a drive…" />
        </div>
      }
    >
      {!driveId ? (
        <EmptyState size="sm" icon={ChartColumn} title="Choose a drive" description="See how its applicants moved from applied to offered." />
      ) : funnel.isLoading ? (
        <Skeleton className="h-56 w-full" />
      ) : funnel.isError ? (
        <ErrorState size="sm" error={funnel.error} onRetry={() => void funnel.refetch()} />
      ) : total === 0 ? (
        <p className="py-10 text-center text-[13px] text-muted-foreground">This drive has no applications yet.</p>
      ) : (
        <div className="flex flex-col gap-4">
          <p className="text-[13px] text-muted-foreground">
            <span className="font-medium text-foreground tabular">{formatInteger(total)}</span> applications ·{" "}
            <span className="font-medium text-foreground tabular">{formatRate(funnel.data?.offerRate)}</span> reached an offer
          </p>
          <ol className="flex flex-col gap-2" aria-label="Funnel stages">
            {stages.map((stage, index) => {
              const exit = stage.stage === "rejected" || stage.stage === "withdrawn";
              return (
                <li key={stage.stage} className="grid grid-cols-[6.5rem_1fr_7.5rem] items-center gap-3 text-[13px]">
                  <span className={cn("truncate", exit ? "text-muted-foreground" : "font-medium")}>{stageLabel(stage.stage)}</span>
                  <div className="relative h-6 overflow-hidden rounded-md bg-muted">
                    <div
                      className="flex h-full items-center rounded-md px-2 text-xs font-medium text-primary-foreground transition-[width] duration-300"
                      style={{
                        width: `${Math.max(total > 0 ? (stage.reached / total) * 100 : 0, stage.reached > 0 ? 6 : 0)}%`,
                        background: exit ? "var(--chart-muted)" : `color-mix(in srgb, var(--chart-1) ${100 - index * 9}%, var(--chart-2))`,
                      }}
                    >
                      <span className={cn("tabular", exit && "text-foreground")}>{formatInteger(stage.reached)}</span>
                    </div>
                  </div>
                  <span className="text-right text-xs text-muted-foreground tabular">
                    {index === 0 ? "100%" : formatRate(stage.conversionRate)}
                    {!exit && index > 0 ? " of previous" : exit ? " of all" : ""}
                  </span>
                </li>
              );
            })}
          </ol>
          <p className="text-xs text-muted-foreground">
            Bars show applications at a stage or beyond it. Rejected and withdrawn applications count towards “Applied” only.
          </p>
        </div>
      )}
    </Panel>
  );
}

export function AnalyticsPage() {
  const { orgId, isStaff, loading } = useOrg();
  const [filters, setFilters] = React.useState<AnalyticsFilters>({});
  const invalidRange = Boolean(filters.from && filters.to && filters.from > filters.to);
  const effective = invalidRange ? {} : filters;
  const overview = useAnalyticsOverview(orgId, effective, { enabled: isStaff });
  const data = overview.data;

  if (!loading && !isStaff) {
    return (
      <EmptyState
        icon={Lock}
        title="Analytics are for placement staff"
        description="Ask your placement officer if you need these numbers."
        action={
          <Button variant="outline" asChild>
            <Link href={orgPath(orgId)}>Back to home</Link>
          </Button>
        }
      />
    );
  }

  return (
    <>
      <PageHeader
        title="Analytics"
        description={data ? `Live placement numbers · computed ${formatDateTime(data.refreshedAt)}` : "Live placement numbers for your scope"}
        actions={<ReportButton orgId={orgId} filters={effective} disabled={invalidRange} />}
      >
        <Filters value={filters} onChange={setFilters} />
      </PageHeader>
      <PageBody className="flex flex-col gap-6">
        {overview.isError ? (
          <div className="rounded-xl border border-border">
            <ErrorState size="sm" error={overview.error} onRetry={() => void overview.refetch()} />
          </div>
        ) : (
          <>
            <KpiGrid
              loading={overview.isLoading}
              items={[
                { label: "Placement rate", value: formatRate(data?.placementRate), hint: "Placed out of participating" },
                {
                  label: "Students placed",
                  value: (
                    <>
                      {formatInteger(data?.placedStudents ?? 0)}
                      <span className="text-sm font-normal text-muted-foreground"> / {formatInteger(data?.participatingStudents ?? 0)}</span>
                    </>
                  ),
                  hint: `${formatInteger(data?.totalStudents ?? 0)} students in scope`,
                },
                { label: "Offers", value: formatInteger(data?.offers ?? 0), hint: `${formatInteger(data?.acceptedOffers ?? 0)} accepted` },
                { label: "Median CTC", value: formatLpa(data?.medianCtc), hint: `Average ${formatLpa(data?.averageCtc)}` },
                { label: "Highest CTC", value: formatLpa(data?.highestCtc), hint: "Across counted offers" },
              ]}
            />
            <div className="grid gap-3 sm:grid-cols-3">
              {[
                { label: "Active drives", value: data?.activeDrives },
                { label: "Applications", value: data?.applications },
                { label: "Companies visited", value: data?.companiesVisited },
              ].map((stat) => (
                <div key={stat.label} className="flex items-center justify-between rounded-lg border border-border bg-card px-4 py-3">
                  <span className="text-[13px] text-muted-foreground">{stat.label}</span>
                  {overview.isLoading ? <Skeleton className="h-5 w-10" /> : <span className="text-[15px] font-semibold tabular">{formatInteger(stat.value ?? 0)}</span>}
                </div>
              ))}
            </div>
          </>
        )}
        <DepartmentsSection orgId={orgId} filters={effective} />
        <div className="grid items-start gap-6 xl:grid-cols-2">
          <CompaniesSection orgId={orgId} filters={effective} />
          <CtcSection orgId={orgId} filters={effective} />
        </div>
        <div className="grid items-start gap-6 xl:grid-cols-2">
          <TrendSection orgId={orgId} filters={effective} />
          <FunnelSection orgId={orgId} filters={effective} />
        </div>
      </PageBody>
    </>
  );
}
