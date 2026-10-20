"use client";

import Link from "next/link";
import { ArrowRight, CalendarRange, Users } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { SimpleBarChart } from "@/components/charts/chart-kit";
import { DriveStatusBadge, KpiGrid, Panel, greeting } from "@/components/dashboard/dashboard-kit";
import { ErrorState } from "@/components/common/error-state";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { LookupValue } from "@/components/records/field-value";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { useAuth } from "@/hooks/use-auth";
import { useCampusDashboard } from "@/lib/api";
import { APPLICATION_STAGES, STAGE_CHART_COLORS, countdown, isRecruiterDashboard, stageLabel } from "@/lib/campushire";
import { formatDate, formatInteger } from "@/lib/metadata/format";
import { orgPath, recordPath } from "@/lib/navigation";
import { cn } from "@/lib/utils";

const ACTIVE = ["published", "registration_closed", "in_progress"];

export function RecruiterDashboard() {
  const { orgId, attribute } = useOrg();
  const { profile } = useAuth();
  const dashboard = useCampusDashboard(orgId);
  const data = isRecruiterDashboard(dashboard.data) ? dashboard.data : undefined;
  const companyId = data?.companyIds[0] ?? attribute("company");
  const drives = data?.drives ?? [];
  const firstName = profile?.name?.split(" ")[0];

  const stageTotals = new Map<string, number>();
  for (const drive of drives) {
    for (const [stage, count] of Object.entries(drive.applicantsByStage)) stageTotals.set(stage, (stageTotals.get(stage) ?? 0) + (count ?? 0));
  }
  const stageKeys = [...APPLICATION_STAGES.filter((stage) => stageTotals.has(stage)), ...[...stageTotals.keys()].filter((stage) => !(APPLICATION_STAGES as readonly string[]).includes(stage))];
  const chartData = drives.map((drive) => ({
    drive: drive.title.length > 22 ? `${drive.title.slice(0, 21)}…` : drive.title,
    ...Object.fromEntries(stageKeys.map((stage) => [stage, drive.applicantsByStage[stage] ?? 0])),
  }));
  const totalApplicants = drives.reduce((sum, drive) => sum + drive.applicants, 0);

  return (
    <>
      <PageHeader
        title={`${greeting()}${firstName ? `, ${firstName}` : ""}`}
        description={
          companyId ? (
            <>
              Hiring at <LookupValue orgId={orgId} targetModuleKey="company" id={companyId} link={false} />
            </>
          ) : (
            "Your drives and applicants"
          )
        }
        actions={
          <Button asChild>
            <Link href={orgPath(orgId, "/recruiter/applicants")}>
              <Users /> Review applicants
            </Link>
          </Button>
        }
      />
      <PageBody className="flex flex-col gap-6">
        {dashboard.isError ? (
          <div className="rounded-xl border border-border">
            <ErrorState size="sm" error={dashboard.error} onRetry={() => void dashboard.refetch()} />
          </div>
        ) : (
          <>
            <KpiGrid
              loading={!data}
              items={[
                { label: "Drives", value: formatInteger(drives.length), hint: "With your company" },
                { label: "Active drives", value: formatInteger(drives.filter((d) => ACTIVE.includes(d.driveStatus)).length), hint: "Published or running" },
                { label: "Applicants", value: formatInteger(totalApplicants), hint: "Across all drives" },
                { label: "Shortlisted", value: formatInteger(stageTotals.get("shortlisted") ?? 0), hint: "Currently at this stage" },
              ]}
            />
            {!data ? (
              <Skeleton className="h-64 rounded-lg" />
            ) : drives.length === 0 ? (
              <div className="rounded-lg border border-dashed border-border-strong">
                <EmptyState
                  icon={CalendarRange}
                  title="No drives yet"
                  description="Your placement cell creates drives for your company. They show up here with their applicants."
                />
              </div>
            ) : (
              <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
                <Panel title="My drives" description="Applicants by current stage" bodyClassName="p-0">
                  <ul className="divide-y divide-border">
                    {drives.map((drive) => {
                      const closes = drive.driveStatus === "published" ? countdown(drive.registrationClosesAt) : null;
                      return (
                        <li key={drive.id} className="flex flex-col gap-2.5 px-4 py-3.5">
                          <div className="flex items-start gap-3">
                            <div className="min-w-0 flex-1">
                              <Link href={recordPath(orgId, "drive", drive.id)} className="focus-ring rounded-sm text-[13px] font-medium hover:underline">
                                {drive.title}
                              </Link>
                              <p className="text-xs text-muted-foreground">
                                {drive.driveDate ? `Drive on ${formatDate(drive.driveDate)}` : "Date to be announced"}
                                {closes && !closes.past ? <span className={cn(closes.urgent && "text-warning-soft-foreground")}> · {closes.label}</span> : null}
                              </p>
                            </div>
                            <DriveStatusBadge status={drive.driveStatus} />
                          </div>
                          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs">
                            <span className="font-medium tabular">{formatInteger(drive.applicants)} applicants</span>
                            {stageKeys
                              .filter((stage) => (drive.applicantsByStage[stage] ?? 0) > 0)
                              .map((stage) => (
                                <span key={stage} className="text-muted-foreground tabular">
                                  {stageLabel(stage)} {drive.applicantsByStage[stage]}
                                </span>
                              ))}
                            <Link
                              href={`${orgPath(orgId, "/recruiter/applicants")}?drive=${drive.id}`}
                              className="focus-ring ml-auto inline-flex items-center gap-1 rounded-sm font-medium text-primary hover:underline"
                            >
                              Applicants <ArrowRight className="size-3" />
                            </Link>
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                </Panel>
                <Panel title="Pipeline" description="Applicants per drive and stage">
                  {stageKeys.length === 0 ? (
                    <p className="py-8 text-center text-[13px] text-muted-foreground">No applicants yet.</p>
                  ) : (
                    <SimpleBarChart
                      data={chartData}
                      xKey="drive"
                      layout="vertical"
                      stacked
                      showLegend
                      height={Math.max(180, chartData.length * 44 + 40)}
                      series={stageKeys.map((stage) => ({ key: stage, label: stageLabel(stage), color: STAGE_CHART_COLORS[stage] }))}
                      label={`Applicants per drive by stage`}
                    />
                  )}
                </Panel>
              </div>
            )}
          </>
        )}
      </PageBody>
    </>
  );
}
