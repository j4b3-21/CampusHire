"use client";

import Link from "next/link";
import { ArrowRight, BadgeCheck, CalendarRange, CircleAlert, CircleCheck, ClipboardList, Award } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { MeterBar } from "@/components/charts/chart-kit";
import { Panel } from "@/components/dashboard/dashboard-kit";
import { ErrorState } from "@/components/common/error-state";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useCampusDashboard, useCampusHireMe } from "@/lib/api";
import { APPLICATION_STAGES, formatLpa, isStudentDashboard, stageLabel } from "@/lib/campushire";
import { formatDate, formatInteger, humanize } from "@/lib/metadata/format";
import { TONE_CLASSES, type Tone } from "@/lib/metadata/tones";
import { orgPath } from "@/lib/navigation";
import { cn } from "@/lib/utils";
import type { StudentDashboard as StudentDashboardData } from "@/types/campushire";

const PLACEMENT_TONES: Record<string, Tone> = { placed: "green", unplaced: "neutral", opted_out: "amber", higher_studies: "sky" };
const OFFER_TONES: Record<string, Tone> = { extended: "indigo", accepted: "green", declined: "neutral", revoked: "red" };

function CompletenessRing({ value }: { value: number }) {
  const radius = 26;
  const circumference = 2 * Math.PI * radius;
  const clamped = Math.max(0, Math.min(100, value));
  return (
    <div className="relative grid size-16 shrink-0 place-items-center">
      <svg viewBox="0 0 64 64" className="size-16 -rotate-90" aria-hidden>
        <circle cx="32" cy="32" r={radius} fill="none" stroke="var(--muted)" strokeWidth="6" />
        <circle
          cx="32"
          cy="32"
          r={radius}
          fill="none"
          stroke={clamped >= 100 ? "var(--success)" : "var(--primary)"}
          strokeWidth="6"
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={circumference * (1 - clamped / 100)}
          className="transition-[stroke-dashoffset] duration-500"
        />
      </svg>
      <span className="absolute text-sm font-semibold tabular">{clamped}%</span>
    </div>
  );
}

function ProfileCard({ orgId, data }: { orgId: string; data: StudentDashboardData }) {
  const { moduleByKey } = useOrg();
  const studentModule = moduleByKey("student");
  const label = (key: string) => studentModule?.fields.find((field) => field.apiKey === key)?.label ?? humanize(key);
  const completeness = data.profile?.completeness ?? 0;
  const missing = data.profile?.missingFields ?? [];
  const complete = missing.length === 0 && completeness >= 100;

  return (
    <Panel title="Your profile" description="Recruiters see this when you apply">
      <div className="flex items-center gap-4">
        <CompletenessRing value={completeness} />
        <div className="min-w-0">
          <p className="text-[13px] font-medium">{complete ? "Your profile is complete" : `${missing.length} thing${missing.length === 1 ? "" : "s"} left to add`}</p>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {complete ? "Keep your resume up to date before each drive." : "A complete profile with a resume helps you get shortlisted."}
          </p>
        </div>
      </div>
      {missing.length > 0 ? (
        <ul className="mt-4 grid gap-1.5 sm:grid-cols-2" aria-label="Missing profile details">
          {missing.map((key) => (
            <li key={key} className="flex items-center gap-2 text-[13px]">
              <CircleAlert className="size-3.5 shrink-0 text-warning" aria-hidden />
              {label(key)}
            </li>
          ))}
        </ul>
      ) : null}
      <Button className="mt-4 w-full sm:w-auto" variant={complete ? "outline" : "default"} asChild>
        <Link href={orgPath(orgId, "/student/profile")}>
          {complete ? "View profile" : "Complete your profile"} <ArrowRight />
        </Link>
      </Button>
    </Panel>
  );
}

function StatLink({ href, icon: Icon, label, value, hint }: { href: string; icon: typeof CalendarRange; label: string; value: number; hint: string }) {
  return (
    <Link
      href={href}
      className="focus-ring group flex items-center gap-3 rounded-lg border border-border bg-card p-4 transition-colors hover:border-border-strong hover:bg-surface"
    >
      <span className="grid size-9 shrink-0 place-items-center rounded-md bg-primary-soft text-primary-soft-foreground">
        <Icon className="size-4" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-xs text-muted-foreground">{label}</span>
        <span className="block text-xl font-semibold tracking-[-0.02em] tabular">{formatInteger(value)}</span>
      </span>
      <span className="hidden text-xs text-muted-foreground group-hover:text-foreground sm:block">{hint}</span>
      <ArrowRight className="size-4 text-muted-foreground transition-transform group-hover:translate-x-0.5" />
    </Link>
  );
}

export function StudentDashboard() {
  const { orgId } = useOrg();
  const me = useCampusHireMe(orgId);
  const dashboard = useCampusDashboard(orgId);
  const data = isStudentDashboard(dashboard.data) ? dashboard.data : undefined;
  const student = data?.student ?? me.data?.student;
  const firstName = student?.fullName.split(" ")[0];
  const stages = data?.applicationsByStage ?? {};
  const stageKeys = [...APPLICATION_STAGES.filter((stage) => stage in stages), ...Object.keys(stages).filter((stage) => !(APPLICATION_STAGES as readonly string[]).includes(stage))];
  const totalApplications = Object.values(stages).reduce<number>((sum, count) => sum + (count ?? 0), 0);
  const maxStage = Math.max(1, ...Object.values(stages).map((count) => count ?? 0));
  const notLinked = me.data?.onboarding.status === "student_not_linked";

  return (
    <>
      <PageHeader
        title={`Hi${firstName ? `, ${firstName}` : ""}`}
        description={
          student ? (
            <span className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-xs">{student.rollNumber}</span>
              {student.placementStatus ? (
                <Badge variant="neutral" className={TONE_CLASSES[PLACEMENT_TONES[student.placementStatus] ?? "neutral"]}>
                  {humanize(student.placementStatus)}
                </Badge>
              ) : null}
              {student.profileVerified ? (
                <Badge variant="success">
                  <BadgeCheck /> Verified
                </Badge>
              ) : null}
            </span>
          ) : (
            "Your placement season at a glance"
          )
        }
      />
      <PageBody className="flex flex-col gap-6">
        {notLinked ? (
          <div role="alert" className="rounded-lg border border-warning/30 bg-warning-soft px-4 py-3 text-[13px] text-warning-soft-foreground">
            Your account isn&apos;t linked to a student record yet. Contact your placement cell.
          </div>
        ) : null}
        {dashboard.isError ? (
          <div className="rounded-xl border border-border">
            <ErrorState size="sm" error={dashboard.error} onRetry={() => void dashboard.refetch()} />
          </div>
        ) : !data ? (
          <div className="grid gap-4 lg:grid-cols-2">
            <Skeleton className="h-56 rounded-lg" />
            <div className="flex flex-col gap-3">
              <Skeleton className="h-[4.5rem] rounded-lg" />
              <Skeleton className="h-[4.5rem] rounded-lg" />
              <Skeleton className="h-[4.5rem] rounded-lg" />
            </div>
          </div>
        ) : (
          <>
            <div className="grid items-start gap-4 lg:grid-cols-2">
              <ProfileCard orgId={orgId} data={data} />
              <div className="flex flex-col gap-3">
                <StatLink
                  href={orgPath(orgId, "/student/drives")}
                  icon={CalendarRange}
                  label="Open drives you're eligible for"
                  value={data.eligibleOpenDrives}
                  hint="Browse drives"
                />
                <StatLink
                  href={orgPath(orgId, "/student/applications")}
                  icon={ClipboardList}
                  label="Applications"
                  value={totalApplications}
                  hint="Track stages"
                />
                <StatLink href={orgPath(orgId, "/student/offers")} icon={Award} label="Offers" value={data.offers.length} hint="Review offers" />
              </div>
            </div>

            <div className="grid items-start gap-4 lg:grid-cols-2">
              <Panel title="My applications by stage" href={orgPath(orgId, "/student/applications")}>
                {stageKeys.length === 0 ? (
                  <p className="py-6 text-center text-[13px] text-muted-foreground">
                    You haven&apos;t applied to any drive yet.{" "}
                    <Link href={orgPath(orgId, "/student/drives")} className="font-medium text-primary hover:underline">
                      Find a drive
                    </Link>
                  </p>
                ) : (
                  <ul className="flex flex-col gap-3">
                    {stageKeys.map((stage) => (
                      <li key={stage} className="grid grid-cols-[7rem_1fr_2rem] items-center gap-3 text-[13px]">
                        <span className="truncate text-muted-foreground">{stageLabel(stage)}</span>
                        <MeterBar
                          value={stages[stage] ?? 0}
                          max={maxStage}
                          color={stage === "rejected" || stage === "withdrawn" ? "var(--chart-muted)" : stage === "offered" || stage === "joined" ? "var(--chart-3)" : "var(--chart-1)"}
                        />
                        <span className="text-right font-medium tabular">{stages[stage]}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </Panel>

              <Panel title="Offers" href={orgPath(orgId, "/student/offers")} bodyClassName="p-0">
                {data.offers.length === 0 ? (
                  <p className="px-4 py-8 text-center text-[13px] text-muted-foreground">No offers yet. They show up here as soon as they&apos;re made.</p>
                ) : (
                  <ul className="divide-y divide-border">
                    {data.offers.map((offer) => (
                      <li key={offer.id} className="flex items-center gap-3 px-4 py-3">
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-[13px] font-medium">{offer.companyName ?? "Company"}</span>
                          <span className="block text-xs text-muted-foreground">
                            {formatLpa(offer.ctc)}
                            {offer.offerType ? ` · ${humanize(offer.offerType)}` : ""}
                            {offer.offerStatus === "extended" && offer.respondBy ? ` · respond by ${formatDate(offer.respondBy)}` : ""}
                          </span>
                        </span>
                        <Badge variant="neutral" className={cn(TONE_CLASSES[OFFER_TONES[offer.offerStatus] ?? "neutral"])}>
                          {offer.offerStatus === "accepted" ? <CircleCheck /> : null}
                          {humanize(offer.offerStatus)}
                        </Badge>
                      </li>
                    ))}
                  </ul>
                )}
              </Panel>
            </div>
          </>
        )}
      </PageBody>
    </>
  );
}
