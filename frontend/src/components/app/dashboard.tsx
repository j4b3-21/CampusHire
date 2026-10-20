"use client";

import Link from "next/link";
import { toast } from "sonner";
import { Check, PackagePlus } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { useAuth } from "@/hooks/use-auth";
import { ApiError, useCampusHireSetup, useRecords } from "@/lib/api";
import { formatInteger } from "@/lib/metadata/format";
import { modulePath } from "@/lib/navigation";
import { ModuleGlyph } from "@/components/common/module-glyph";
import { cn } from "@/lib/utils";
import type { ModuleDefinition } from "@/types/metadata";

const KPIS = [
  { label: "Placement rate", hint: "Placed students out of eligible students" },
  { label: "Students placed", hint: "With at least one accepted offer" },
  { label: "Offers made", hint: "Across all drives this season" },
  { label: "Median CTC", hint: "Full-time offers" },
];

function greeting() {
  const hour = new Date().getHours();
  if (hour < 12) return "Good morning";
  if (hour < 17) return "Good afternoon";
  return "Good evening";
}

function useModuleCount(orgId: string, moduleKey: string | undefined) {
  const query = useRecords(orgId, moduleKey, { size: 1 }, { enabled: Boolean(moduleKey) });
  return { count: query.data?.totalElements, loading: query.isLoading, error: query.isError };
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
  const installed = Boolean(moduleByKey("student"));

  const steps = [
    { label: "Install CampusHire modules", done: installed, href: undefined },
    { label: "Import students from Excel", done: (students.count ?? 0) > 0, href: `/app/${orgId}/admin/import` },
    { label: "Add the companies you work with", done: (companies.count ?? 0) > 0, href: modulePath(orgId, "company") },
    { label: "Create your first drive", done: (drives.count ?? 0) > 0, href: modulePath(orgId, "drive", "/new") },
  ];
  return steps;
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
                  <Link
                    href={step.href}
                    className="focus-ring -mx-2 flex items-center gap-3 rounded-md px-2 py-1.5 transition-colors hover:bg-accent"
                  >
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

function InstallCallout({ orgId }: { orgId: string }) {
  const setup = useCampusHireSetup(orgId);
  const install = async () => {
    try {
      await setup.mutateAsync();
      toast.success("CampusHire installed");
    } catch (error) {
      if (error instanceof ApiError && error.isNotFound) {
        toast.info("Setup isn't available on this server yet", {
          description: "The CampusHire backend will provide it. Modules you create by hand still work.",
        });
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
          Adds students, companies, drives, applications and offers, with roles for every kind of user. Safe to run
          again at any time.
        </p>
      </div>
      <Button onClick={install} disabled={setup.isPending}>
        {setup.isPending ? <Spinner className="text-primary-foreground" /> : null}
        Install modules
      </Button>
    </div>
  );
}

export function Dashboard() {
  const { orgId, organization, readableModules, isAdmin, loading, modules } = useOrg();
  const { profile } = useAuth();
  const steps = useSetupSteps(orgId);
  const showChecklist = isAdmin && !loading && steps.some((step) => !step.done);
  const firstName = profile?.name?.split(" ")[0];

  return (
    <>
      <PageHeader
        title={`${greeting()}${firstName ? `, ${firstName}` : ""}`}
        description={organization ? `Placement overview for ${organization.name}` : undefined}
      />
      <PageBody className="flex flex-col gap-6">
        {isAdmin && !loading && modules.length === 0 ? <InstallCallout orgId={orgId} /> : null}

        <section aria-label="Key numbers" className="grid gap-px overflow-hidden rounded-xl border border-border bg-border sm:grid-cols-2 xl:grid-cols-4">
          {KPIS.map((kpi) => (
            <div key={kpi.label} className="flex flex-col gap-3 bg-card p-5">
              <p className="text-[13px] font-medium text-muted-foreground">{kpi.label}</p>
              <Skeleton className="h-7 w-24 animate-none" />
              <p className="text-xs text-subtle-foreground">{kpi.hint}</p>
            </div>
          ))}
        </section>
        <p className="-mt-3 text-xs text-muted-foreground">
          Placement analytics fill in as drives run and offers are recorded.
        </p>

        <div className={cn("grid items-start gap-6", showChecklist && "lg:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]")}>
          <section aria-labelledby="modules-heading" className="flex flex-col gap-3">
            <h2 id="modules-heading" className="text-sm font-semibold">
              Records
            </h2>
            {loading ? (
              <div className="grid gap-2 sm:grid-cols-2">
                {Array.from({ length: 6 }, (_, i) => (
                  <Skeleton key={i} className="h-14 rounded-lg" />
                ))}
              </div>
            ) : readableModules.length > 0 ? (
              <div className={cn("grid gap-2 sm:grid-cols-2", !showChecklist && "lg:grid-cols-3 2xl:grid-cols-4")}>
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
          {showChecklist ? <SetupChecklist steps={steps} /> : null}
        </div>
      </PageBody>
    </>
  );
}
