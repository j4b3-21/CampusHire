"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ArrowLeft, SquareKanban, List, Lock, Plus, SearchX, Upload } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { PipelineBoard } from "@/components/board/pipeline-board";
import { ErrorState } from "@/components/common/error-state";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { RecordDetail } from "@/components/records/record-detail";
import { RecordForm } from "@/components/records/record-form";
import { ExportMenu } from "@/components/records/export-menu";
import { RecordTable } from "@/components/records/record-table";
import { useTableUrlState } from "@/components/records/use-table-url-state";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { ApiError, useModule, useRecord, useWorkflow } from "@/lib/api";
import { importPath, modulePath, orgPath, recordPath } from "@/lib/navigation";
import { cn } from "@/lib/utils";
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

/** List / Board switch, shown when the module has a workflow. Keeps search and filters. */
function ViewToggle({ orgId, moduleKey, view }: { orgId: string; moduleKey: string; view: "list" | "board" }) {
  const searchParams = useSearchParams();
  const workflow = useWorkflow(orgId, moduleKey);
  if (!workflow.data) return null;
  const keep = new URLSearchParams();
  searchParams.forEach((value, key) => {
    if (key === "q" || key.startsWith("filter[")) keep.append(key, value);
  });
  const qs = keep.toString() ? `?${keep.toString()}` : "";
  const items = [
    { key: "list", label: "List", icon: List, href: modulePath(orgId, moduleKey, qs) },
    { key: "board", label: "Board", icon: SquareKanban, href: modulePath(orgId, moduleKey, `/board${qs}`) },
  ] as const;
  return (
    <nav aria-label="View" className="inline-flex items-center gap-0.5 rounded-md bg-muted p-0.5">
      {items.map((item) => (
        <Link
          key={item.key}
          href={item.href}
          aria-current={view === item.key ? "page" : undefined}
          className={cn(
            "focus-ring inline-flex h-7 items-center gap-1.5 rounded-sm px-2.5 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground [&_svg]:size-3.5",
            view === item.key && "bg-background text-foreground shadow-xs"
          )}
        >
          <item.icon /> {item.label}
        </Link>
      ))}
    </nav>
  );
}

/** Export with the list's current filters, sort and search (read from the URL like the table). */
function ModuleExport({ orgId, moduleKey }: { orgId: string; moduleKey: string }) {
  const { query } = useTableUrlState();
  if (query.deleted) return null;
  return <ExportMenu orgId={orgId} moduleKey={moduleKey} params={{ sort: query.sort, q: query.q, filters: query.filters }} />;
}

function NewRecordButton({ orgId, module, capabilities }: { orgId: string; module: ModuleDefinition; capabilities: ModuleCapabilities }) {
  if (!capabilities.create) return null;
  return (
    <Button asChild>
      <Link href={modulePath(orgId, module.apiKey, "/new")}>
        <Plus /> New {module.labelSingular.toLowerCase()}
      </Link>
    </Button>
  );
}

function BoardContent({ module, capabilities }: { module: ModuleDefinition; capabilities: ModuleCapabilities }) {
  const { orgId, isAdmin } = useOrg();
  const workflow = useWorkflow(orgId, module.apiKey);
  if (workflow.isLoading) {
    return (
      <div className="flex gap-3 px-4 sm:px-6 lg:px-8">
        {Array.from({ length: 4 }, (_, i) => (
          <Skeleton key={i} className="h-96 w-72 shrink-0 rounded-xl" />
        ))}
      </div>
    );
  }
  if (workflow.isError) return <ErrorState error={workflow.error} onRetry={() => void workflow.refetch()} />;
  if (!workflow.data) {
    return (
      <EmptyState
        icon={SquareKanban}
        title={`${module.labelPlural} don't have a pipeline yet`}
        description="A board needs a workflow: stages on one pick-list field and the moves allowed between them."
        action={
          <>
            <Button variant="outline" asChild>
              <Link href={modulePath(orgId, module.apiKey)}>
                <List /> Back to the list
              </Link>
            </Button>
            {isAdmin ? (
              <Button asChild>
                <Link href={orgPath(orgId, `/admin/workflows/${module.apiKey}`)}>Set up a workflow</Link>
              </Button>
            ) : null}
          </>
        }
      />
    );
  }
  const field = module.fields.find((f) => f.apiKey === workflow.data!.field);
  if (field && !field.filterable) {
    return (
      <EmptyState
        icon={SquareKanban}
        title="The stage field can't be filtered"
        description={`Mark “${field.label}” as filterable in Customize modules to group ${module.labelPlural.toLowerCase()} by stage.`}
      />
    );
  }
  if (capabilities.hiddenFields.includes(workflow.data.field)) {
    return <NoAccess orgId={orgId} module={module} action="read" />;
  }
  return <PipelineBoard module={module} capabilities={capabilities} workflow={workflow.data} />;
}

export function ModuleBoardPage({ moduleKey }: { moduleKey: string }) {
  const { orgId } = useOrg();
  return (
    <ModuleGate moduleKey={moduleKey} action="read">
      {(module, capabilities) => (
        <div className="flex min-h-0 flex-1 flex-col">
          <PageHeader
            icon={<ModuleIcon moduleKey={module.apiKey} />}
            title={module.labelPlural}
            description={module.description || `${module.labelPlural} by stage.`}
            actions={
              <>
                <ViewToggle orgId={orgId} moduleKey={module.apiKey} view="board" />
                <NewRecordButton orgId={orgId} module={module} capabilities={capabilities} />
              </>
            }
          />
          <BoardContent module={module} capabilities={capabilities} />
        </div>
      )}
    </ModuleGate>
  );
}

export function ModuleListPage({ moduleKey }: { moduleKey: string }) {
  const { orgId } = useOrg();
  return (
    <ModuleGate moduleKey={moduleKey} action="read">
      {(module, capabilities) => {
        const newButton = <NewRecordButton orgId={orgId} module={module} capabilities={capabilities} />;
        return (
          <>
            <PageHeader
              icon={<ModuleIcon moduleKey={module.apiKey} />}
              title={module.labelPlural}
              description={module.description || `All ${module.labelPlural.toLowerCase()} you have access to.`}
              actions={
                <>
                  <ViewToggle orgId={orgId} moduleKey={module.apiKey} view="list" />
                  {capabilities.create ? (
                    <SimpleTooltip label="Import from Excel or CSV">
                      <Button variant="outline" asChild>
                        <Link href={importPath(orgId, module.apiKey)}>
                          <Upload /> <span className="hidden sm:inline">Import</span>
                        </Link>
                      </Button>
                    </SimpleTooltip>
                  ) : null}
                  {newButton}
                </>
              }
            />
            <PageBody>
              <RecordTable
                orgId={orgId}
                module={module}
                capabilities={capabilities}
                emptyAction={capabilities.create ? newButton : null}
                toolbarActions={<ModuleExport orgId={orgId} moduleKey={module.apiKey} />}
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
