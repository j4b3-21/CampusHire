"use client";

import Link from "next/link";
import { toast } from "sonner";
import { AlarmClock, Award, CalendarRange, Check, IndianRupee, PackagePlus, Percent, Trophy, Users } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { SimpleBarChart } from "@/components/charts/chart-kit";
import { DriveStatusBadge, KpiGrid, MiniStat, Panel, greeting } from "@/components/dashboard/dashboard-kit";
import { ErrorState } from "@/components/common/error-state";
import { ModuleGlyph } from "@/components/common/module-glyph";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { LookupValue } from "@/components/records/field-value";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { useAuth } from "@/hooks/use-auth";
import {
  ApiError,
  errorMessage,
  useAnalyticsOverview,
  useCampusDashboard,
  useCampusHireSetup,
  useCampusHireStatus,
  useInstalledPacks,
  useRecords,
} from "@/lib/api";
import { countdown, formatLpa, formatRate, isStaffDashboard } from "@/lib/campushire";
import { formatDate, formatInteger, humanize } from "@/lib/metadata/format";
import { ANALYTICS_NAV, modulePath, orgPath, recordPath } from "@/lib/navigation";
import { cn } from "@/lib/utils";
import type { StaffDashboard as StaffDashboardData } from "@/types/campushire";
import type { ModuleDefinition } from "@/types/metadata";

const PIPELINE_ORDER = ["lead", "contacted", "confirmed", "visited", "inactive"];

function useModuleCount(orgId: string, moduleKey: string | undefined) {
  const query = useRecords(orgId, moduleKey, { size: 1 }, { enabled: Boolean(moduleKey) });
  return { count: query.data?.totalElements, loading: query.isLoading };
}

function ModuleTile({ orgId, module }: { orgId: string; module: ModuleDefinition }) {
  const { count, loading } = useModuleCount(orgId, module.apiKey);
  return (
    <Link
      href={modulePath(orgId, module.apiKey)}
      className="focus-ring group flex items-center gap-3 rounded-lg border border-border bg-card px-3.5 py-3 transition-colors hover:border-border-strong hover:bg-surface"
    >
      <span className="grid size-8 shrink-0 place-items-center rounded-md border border-border bg-background text-muted-foreground transition-colors group-hover:text-primary">
        <ModuleGlyph moduleKey={module.apiKey} className="size-4" />
      </span>
      <span className="min-w-0 flex-1 truncate text-[13px] font-medium">{module.labelPlural}</span>
      {loading ? (
        <Skeleton className="h-4 w-8" />
      ) : (
        <span className="text-[13px] text-muted-foreground tabular">{count === undefined ? "–" : formatInteger(count)}</span>
      )}
    </Link>
  );
}

interface SetupStep {
  label: string;
  done: boolean;
  href?: string;
}

function useSetupSteps(orgId: string): SetupStep[] {
  const { moduleByKey } = useOrg();
  const students = useModuleCount(orgId, moduleByKey("student")?.apiKey);
  const companies = useModuleCount(orgId, moduleByKey("company")?.apiKey);
  const drives = useModuleCount(orgId, moduleByKey("drive")?.apiKey);
  return [
    { label: "Install CampusHire modules", done: Boolean(moduleByKey("student")) },
    { label: "Import students from Excel", done: (students.count ?? 0) > 0, href: `/app/${orgId}/admin/import?module=student` },
    { label: "Add the companies you work with", done: (companies.count ?? 0) > 0, href: modulePath(orgId, "company") },
    { label: "Create your first drive", done: (drives.count ?? 0) > 0, href: modulePath(orgId, "drive", "/new") },
  ];
}

function SetupChecklist({ steps }: { steps: SetupStep[] }) {
  const doneCount = steps.filter((step) => step.done).length;
  const installed = steps[0]?.done ?? false;
  return (
    <Card>
      <CardHeader>
        <CardTitle>Get your placement season ready</CardTitle>
        <CardDescription>
          {doneCount} of {steps.length} done
        </CardDescription>
      </CardHeader>
      <CardContent className="pt-4">
        <ol className="grid gap-1">
          {steps.map((step, index) => {
            const content = (
              <>
                <span
                  className={cn(
                    "grid size-5 shrink-0 place-items-center rounded-full border text-[11px] font-medium tabular",
                    step.done ? "border-primary bg-primary text-primary-foreground" : "border-border-strong text-muted-foreground"
                  )}
                >
                  {step.done ? <Check className="size-3" strokeWidth={3} /> : index + 1}
                </span>
                <span className={cn("text-[13px]", step.done && "text-muted-foreground line-through decoration-border-strong")}>
                  {step.label}
                </span>
              </>
            );
            return (
              <li key={step.label}>
                {step.href && !step.done && installed ? (
                  <Link href={step.href} className="focus-ring -mx-2 flex items-center gap-3 rounded-md px-2 py-1.5 transition-colors hover:bg-accent">
                    {content}
                  </Link>
                ) : (
                  <div className="-mx-2 flex items-center gap-3 px-2 py-1.5">{content}</div>
                )}
              </li>
            );
          })}
        </ol>
      </CardContent>
    </Card>
  );
}

function useCampusHireInstalled(orgId: string, enabled: boolean) {
  const status = useCampusHireStatus(orgId, { enabled });
  const packs = useInstalledPacks(orgId, { enabled });
  if (status.isLoading || packs.isLoading) return undefined;
  if (status.data?.installed) return true;
  return (packs.data ?? []).some((pack) => pack.packKey === "campushire");
}

function InstallCallout({ orgId }: { orgId: string }) {
  const setup = useCampusHireSetup(orgId);
  const install = async () => {
    try {
      await setup.mutateAsync(undefined);
      toast.success("CampusHire installed", { description: "Modules, roles and workflows are ready." });
    } catch (error) {
      if (error instanceof ApiError && (error.isNotFound || error.status === 405 || error.status === 501)) {
        toast.info("Setup isn't available on this server yet", {
          description: "The CampusHire backend will provide it. Modules you create by hand still work.",
        });
      } else {
        toast.error("Couldn't install CampusHire", { description: errorMessage(error) });
      }
    }
  };
  return (
    <div className="flex flex-col gap-4 rounded-xl border border-primary/25 bg-primary-soft/50 p-5 sm:flex-row sm:items-center">
      <span className="grid size-10 shrink-0 place-items-center rounded-lg bg-primary text-primary-foreground">
        <PackagePlus className="size-5" />
      </span>
      <div className="flex-1">
        <p className="text-sm font-semibold">Install CampusHire for this institute</p>
        <p className="mt-0.5 text-[13px] text-muted-foreground">
          Adds students, companies, drives, applications and offers, with roles for every kind of user. Safe to run again
          at any time.
        </p>
      </div>
      <Button onClick={install} disabled={setup.isPending}>
        {setup.isPending ? <Spinner className="text-primary-foreground" /> : null}
        Install modules
      </Button>
    </div>
  );
}

function UpcomingDrives({ orgId, data }: { orgId: string; data: StaffDashboardData | undefined }) {
  const drives = data?.upcomingDrives ?? [];
  return (
    <Panel title="Upcoming drives" description="Next published, closed or running drives" href={modulePath(orgId, "drive")} bodyClassName="p-0">
      {!data ? (
        <div className="flex flex-col gap-3 p-4">
          {Array.from({ length: 3 }, (_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      ) : drives.length === 0 ? (
        <p className="px-4 py-8 text-center text-[13px] text-muted-foreground">
          No published drives yet.{" "}
          <Link href={modulePath(orgId, "drive")} className="font-medium text-primary hover:underline">
            Open drives
          </Link>
        </p>
      ) : (
        <ul className="divide-y divide-border">
          {drives.map((drive) => {
            const closes = drive.driveStatus === "published" ? countdown(drive.registrationClosesAt) : null;
            return (
              <li key={drive.id}>
                <Link
                  href={recordPath(orgId, "drive", drive.id)}
                  className="focus-ring flex items-center gap-3 px-4 py-3 transition-colors hover:bg-surface"
                >
                  <span className="grid size-9 shrink-0 place-items-center rounded-md border border-border bg-background text-center leading-none">
                    {drive.driveDate ? (
                      <span className="flex flex-col">
                        <span className="text-[13px] font-semibold tabular">{formatDate(drive.driveDate).split(" ")[0]}</span>
                        <span className="text-[10px] uppercase text-muted-foreground">{formatDate(drive.driveDate).split(" ")[1]}</span>
                      </span>
                    ) : (
                      <CalendarRange className="size-4 text-muted-foreground" />
                    )}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[13px] font-medium">{drive.title}</span>
                    <span className="block truncate text-xs text-muted-foreground">
                      {drive.companyName ?? "Company"}
                      {closes && !closes.past ? (
                        <span className={cn(closes.urgent && "text-warning-soft-foreground")}> · {closes.label}</span>
                      ) : null}
                    </span>
                  </span>
                  <DriveStatusBadge status={drive.driveStatus} />
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </Panel>
  );
}

function FollowUps({ orgId, followUps }: { orgId: string; followUps: NonNullable<StaffDashboardData["followUps"]> }) {
  return (
    <Panel
      title="Follow-ups"
      description={
        <span className="tabular">
          {followUps.dueToday} due today · <span className={cn(followUps.overdue > 0 && "text-destructive")}>{followUps.overdue} overdue</span>
        </span>
      }
      href={modulePath(orgId, "follow_up")}
      bodyClassName="p-0"
    >
      {followUps.items.length === 0 ? (
        <p className="px-4 py-8 text-center text-[13px] text-muted-foreground">Nothing due. Nice.</p>
      ) : (
        <ul className="divide-y divide-border">
          {followUps.items.map((item) => (
            <li key={item.id}>
              <Link href={recordPath(orgId, "follow_up", item.id)} className="focus-ring flex items-center gap-3 px-4 py-2.5 transition-colors hover:bg-surface">
                <AlarmClock className={cn("size-4 shrink-0", item.overdue ? "text-destructive" : "text-muted-foreground")} aria-hidden />
                <div className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] font-medium">{item.title}</span>
                  {item.companyId ? (
                    <div className="truncate text-xs text-muted-foreground">
                      <LookupValue orgId={orgId} targetModuleKey="company" id={item.companyId} link={false} />
                    </div>
                  ) : null}
                </div>
                <Badge variant={item.overdue ? "destructive" : "neutral"} className="tabular">
                  {item.overdue ? "Overdue · " : ""}
                  {formatDate(item.dueDate)}
                </Badge>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function CompaniesByStage({ orgId, stages }: { orgId: string; stages: Record<string, number> }) {
  const keys = [...PIPELINE_ORDER.filter((key) => key in stages), ...Object.keys(stages).filter((key) => !PIPELINE_ORDER.includes(key))];
  const data = keys.map((key) => ({ stage: humanize(key), companies: stages[key] ?? 0 }));
  const total = data.reduce((sum, row) => sum + row.companies, 0);
  return (
    <Panel title="Companies by pipeline stage" description={`${formatInteger(total)} companies in the CRM`} href={modulePath(orgId, "company")}>
      {total === 0 ? (
        <p className="py-8 text-center text-[13px] text-muted-foreground">No companies yet.</p>
      ) : (
        <SimpleBarChart
          data={data}
          xKey="stage"
          series={[{ key: "companies", label: "Companies" }]}
          layout="vertical"
          height={Math.max(150, data.length * 36)}
          colorByIndex
          yWidth={84}
          label={`Companies by pipeline stage: ${data.map((row) => `${row.stage} ${row.companies}`).join(", ")}`}
        />
      )}
    </Panel>
  );
}

export function StaffDashboard() {
  const { orgId, organization, readableModules, isAdmin, persona } = useOrg();
  const installed = useCampusHireInstalled(orgId, isAdmin);
  const { profile } = useAuth();
  const steps = useSetupSteps(orgId);
  const dashboard = useCampusDashboard(orgId, { enabled: installed !== false });
  const overview = useAnalyticsOverview(orgId, {}, { enabled: installed !== false && persona !== "member" });
  const showChecklist = isAdmin && steps.some((step) => !step.done);
  const firstName = profile?.name?.split(" ")[0];
  const data = isStaffDashboard(dashboard.data) ? dashboard.data : undefined;
  const coordinator = persona === "coordinator" || data?.role === "coordinator";
  const kpiLoading = dashboard.isLoading || overview.isLoading;
  const placement = data?.placement;
  const rate = overview.data?.placementRate ?? (placement?.placementPercentage != null ? placement.placementPercentage / 100 : null);

  return (
    <>
      <PageHeader
        title={`${greeting()}${firstName ? `, ${firstName}` : ""}`}
        description={
          organization
            ? coordinator
              ? `Placement overview for your departments at ${organization.name}`
              : `Placement overview for ${organization.name}`
            : undefined
        }
        actions={
          persona !== "member" ? (
            <Button variant="outline" asChild>
              <Link href={orgPath(orgId, ANALYTICS_NAV.path)}>
                <ANALYTICS_NAV.icon /> Analytics
              </Link>
            </Button>
          ) : null
        }
      />
      <PageBody className="flex flex-col gap-6">
        {isAdmin && installed === false ? <InstallCallout orgId={orgId} /> : null}

        {dashboard.isError && !(dashboard.error instanceof ApiError && dashboard.error.isForbidden) ? (
          <div className="rounded-xl border border-border">
            <ErrorState size="sm" error={dashboard.error} onRetry={() => void dashboard.refetch()} />
          </div>
        ) : persona !== "member" ? (
          <>
            <KpiGrid
              loading={kpiLoading}
              items={[
                { label: "Placement rate", icon: Percent, value: formatRate(rate), hint: "Placed out of participating students" },
                {
                  label: "Students placed",
                  icon: Users,
                  value: (
                    <>
                      {formatInteger(overview.data?.placedStudents ?? placement?.placedStudents ?? 0)}
                      <span className="text-sm font-normal text-muted-foreground">
                        {" "}
                        / {formatInteger(overview.data?.participatingStudents ?? placement?.participatingStudents ?? 0)}
                      </span>
                    </>
                  ),
                  hint: "With an extended or accepted offer",
                },
                { label: "Offers", icon: Award, value: formatInteger(overview.data?.offers ?? placement?.offers ?? 0), hint: "Extended and accepted" },
                { label: "Median CTC", icon: IndianRupee, value: formatLpa(overview.data?.medianCtc), hint: `Average ${formatLpa(overview.data?.averageCtc ?? placement?.averageCtc)}` },
                { label: "Highest CTC", icon: Trophy, value: formatLpa(overview.data?.highestCtc ?? placement?.highestCtc), hint: "Across counted offers" },
              ]}
            />

            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              <MiniStat label="Students" value={data ? formatInteger(data.counts.students) : "–"} href={modulePath(orgId, "student")} />
              <MiniStat label="Open drives" value={data ? formatInteger(data.counts.openDrives) : "–"} href={modulePath(orgId, "drive")} />
              <MiniStat
                label="Applications this week"
                value={data ? formatInteger(data.counts.applicationsThisWeek) : "–"}
                href={modulePath(orgId, "application")}
              />
              <MiniStat label="Active offers" value={data ? formatInteger(data.counts.activeOffers) : "–"} href={modulePath(orgId, "offer")} />
            </div>

            <div className="grid items-start gap-6 lg:grid-cols-2">
              <UpcomingDrives orgId={orgId} data={data} />
              {data?.followUps ? (
                <FollowUps orgId={orgId} followUps={data.followUps} />
              ) : !data && !coordinator ? (
                <Skeleton className="h-48 rounded-lg" />
              ) : null}
              {data && !coordinator ? <CompaniesByStage orgId={orgId} stages={data.companiesByStage} /> : null}
              {showChecklist ? <SetupChecklist steps={steps} /> : null}
            </div>
          </>
        ) : null}

        <section aria-labelledby="modules-heading" className="flex flex-col gap-3">
          <h2 id="modules-heading" className="text-sm font-semibold">
            Records
          </h2>
          {readableModules.length > 0 ? (
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4">
              {readableModules.map((module) => (
                <ModuleTile key={module.apiKey} orgId={orgId} module={module} />
              ))}
            </div>
          ) : (
            <div className="rounded-lg border border-dashed border-border-strong px-5 py-8 text-center text-[13px] text-muted-foreground">
              {isAdmin
                ? "No modules yet. Install CampusHire to get started."
                : "Nothing has been shared with you yet. Your placement cell will give you access."}
            </div>
          )}
        </section>
      </PageBody>
    </>
  );
}
