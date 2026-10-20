"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowLeft, Download, Lock, Plus, SearchX } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { ErrorState } from "@/components/common/error-state";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { RecordDetail } from "@/components/records/record-detail";
import { RecordForm } from "@/components/records/record-form";
import { RecordTable } from "@/components/records/record-table";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { ApiError, useModule, useRecord } from "@/lib/api";
import { modulePath, orgPath, recordPath } from "@/lib/navigation";
import { ModuleGlyph } from "@/components/common/module-glyph";
import type { ModuleDefinition } from "@/types/metadata";
import type { ModuleCapabilities } from "@/types/org";

type Action = "read" | "create" | "update";

function NoAccess({ orgId, module, action }: { orgId: string; module: ModuleDefinition; action: Action }) {
  const verb = action === "create" ? "add" : action === "update" ? "edit" : "view";
  return (
    <EmptyState
      icon={Lock}
      title={`You can't ${verb} ${module.labelPlural.toLowerCase()}`}
      description="Your role doesn't include this. Ask your placement officer if you need it."
      action={
        <Button variant="outline" asChild>
          <Link href={action === "read" ? orgPath(orgId) : modulePath(orgId, module.apiKey)}>
            <ArrowLeft /> Go back
          </Link>
        </Button>
      }
    />
  );
}

function HeaderSkeleton() {
  return (
    <div className="flex flex-col gap-6 px-4 pt-6 sm:px-6 lg:px-8">
      <div className="flex items-center gap-3">
        <Skeleton className="size-9 rounded-lg" />
        <div className="flex flex-col gap-2">
          <Skeleton className="h-5 w-40" />
          <Skeleton className="h-3.5 w-64" />
        </div>
      </div>
      <Skeleton className="h-8 w-full max-w-md" />
      <Skeleton className="h-80 w-full rounded-lg" />
    </div>
  );
}

/** Loads the module definition and checks the caller's capability before rendering. */
function ModuleGate({
  moduleKey,
  action,
  children,
}: {
  moduleKey: string;
  action: Action;
  children: (module: ModuleDefinition, capabilities: ModuleCapabilities) => React.ReactNode;
}) {
  const { orgId, capabilities, loading } = useOrg();
  const definition = useModule(orgId, moduleKey);

  if (definition.isLoading || loading) return <HeaderSkeleton />;
  if (definition.isError || !definition.data) {
    if (definition.error instanceof ApiError && definition.error.isNotFound) {
      return (
        <EmptyState
          icon={SearchX}
          title="Module not found"
          description="It may have been archived or renamed."
          action={
            <Button variant="outline" asChild>
              <Link href={orgPath(orgId)}>Back to dashboard</Link>
            </Button>
          }
        />
      );
    }
    return <ErrorState error={definition.error} onRetry={() => void definition.refetch()} />;
  }
  const caps = capabilities(moduleKey);
  if (!caps.read || (action === "create" && !caps.create) || (action === "update" && !caps.update)) {
    return <NoAccess orgId={orgId} module={definition.data} action={action} />;
  }
  return <>{children(definition.data, caps)}</>;
}

function ModuleIcon({ moduleKey }: { moduleKey: string }) {
  return (
    <span className="mt-0.5 grid size-9 shrink-0 place-items-center rounded-lg border border-border bg-surface text-muted-foreground">
      <ModuleGlyph moduleKey={moduleKey} className="size-[18px]" />
    </span>
  );
}

export function ModuleListPage({ moduleKey }: { moduleKey: string }) {
  const { orgId } = useOrg();
  return (
    <ModuleGate moduleKey={moduleKey} action="read">
      {(module, capabilities) => {
        const newButton = capabilities.create ? (
          <Button asChild>
            <Link href={modulePath(orgId, module.apiKey, "/new")}>
              <Plus /> New {module.labelSingular.toLowerCase()}
            </Link>
          </Button>
        ) : null;
        return (
          <>
            <PageHeader
              icon={<ModuleIcon moduleKey={module.apiKey} />}
              title={module.labelPlural}
              description={module.description || `All ${module.labelPlural.toLowerCase()} you have access to.`}
              actions={newButton}
            />
            <PageBody>
              <RecordTable
                orgId={orgId}
                module={module}
                capabilities={capabilities}
                emptyAction={newButton}
                toolbarActions={
                  <SimpleTooltip label="Excel and CSV export is coming with data import">
                    <span tabIndex={0} className="focus-ring rounded-md">
                      <Button variant="outline" size="sm" disabled aria-label="Export">
                        <Download /> <span className="hidden sm:inline">Export</span>
                      </Button>
                    </span>
                  </SimpleTooltip>
                }
              />
            </PageBody>
          </>
        );
      }}
    </ModuleGate>
  );
}

export function ModuleNewPage({ moduleKey }: { moduleKey: string }) {
  const router = useRouter();
  const { orgId } = useOrg();
  return (
    <ModuleGate moduleKey={moduleKey} action="create">
      {(module, capabilities) => (
        <>
          <PageHeader
            icon={<ModuleIcon moduleKey={module.apiKey} />}
            title={`New ${module.labelSingular.toLowerCase()}`}
            description="Fields marked * are required."
          />
          <PageBody className="max-w-5xl pb-0">
            <RecordForm
              orgId={orgId}
              module={module}
              capabilities={capabilities}
              onSaved={(record) => router.push(recordPath(orgId, module.apiKey, record.id))}
              onCancel={() => router.push(modulePath(orgId, module.apiKey))}
            />
          </PageBody>
        </>
      )}
    </ModuleGate>
  );
}

export function ModuleDetailPage({ moduleKey, recordId }: { moduleKey: string; recordId: string }) {
  return (
    <ModuleGate moduleKey={moduleKey} action="read">
      {(module) => <RecordDetail module={module} recordId={recordId} />}
    </ModuleGate>
  );
}

function EditRecord({
  module,
  capabilities,
  recordId,
}: {
  module: ModuleDefinition;
  capabilities: ModuleCapabilities;
  recordId: string;
}) {
  const router = useRouter();
  const { orgId } = useOrg();
  const record = useRecord(orgId, module.apiKey, recordId, { silent: true });
  const detailHref = recordPath(orgId, module.apiKey, recordId);

  if (record.isLoading) return <HeaderSkeleton />;
  if (record.isError || !record.data) return <ErrorState error={record.error} onRetry={() => void record.refetch()} />;

  return (
    <>
      <PageHeader
        icon={<ModuleIcon moduleKey={module.apiKey} />}
        title={`Edit ${module.labelSingular.toLowerCase()}`}
        description="Only the fields you change are saved."
      />
      <PageBody className="max-w-5xl pb-0">
        <RecordForm
          key={record.data.id}
          orgId={orgId}
          module={module}
          capabilities={capabilities}
          record={record.data}
          onSaved={() => router.push(detailHref)}
          onCancel={() => router.push(detailHref)}
          onReload={async () => (await record.refetch()).data}
        />
      </PageBody>
    </>
  );
}

export function ModuleEditPage({ moduleKey, recordId }: { moduleKey: string; recordId: string }) {
  return (
    <ModuleGate moduleKey={moduleKey} action="update">
      {(module, capabilities) => <EditRecord module={module} capabilities={capabilities} recordId={recordId} />}
    </ModuleGate>
  );
}
