"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { toast } from "sonner";
import { ArrowLeft, Copy, MoreHorizontal, Pencil, SearchX, Trash2, Undo2 } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { ConfirmDeleteDialog, useRecordActions } from "@/components/records/record-actions";
import { FieldValue, RecordTitle } from "@/components/records/field-value";
import { TransitionTimeline, WorkflowMoveMenu } from "@/components/workflow/record-workflow";
import { StateBadge, WorkflowValue } from "@/components/workflow/state-badge";
import { RecordHistory } from "@/components/records/record-history";
import { RelatedRecords, useRelatedSources } from "@/components/records/related-records";
import { ErrorState } from "@/components/common/error-state";
import { PageBody } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { useMembers, useRecord, useWorkflow, type VersionedRecord } from "@/lib/api";
import { ApiError } from "@/lib/api/errors";
import { displayField, recordTitle, visibleFields } from "@/lib/metadata/fields";
import { formatDateTime, formatRelative, shortId } from "@/lib/metadata/format";
import { gridColumnsClass, isWideField, resolveLayout } from "@/lib/metadata/layout";
import { modulePath, recordPath } from "@/lib/navigation";
import { ModuleGlyph } from "@/components/common/module-glyph";
import { cn } from "@/lib/utils";
import { stateByKey } from "@/lib/workflow";
import type { ModuleDefinition } from "@/types/metadata";
import type { ModuleCapabilities } from "@/types/org";
import type { Workflow } from "@/types/workflow";

function MetaRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="min-w-0 text-[13px]">{children}</dd>
    </div>
  );
}

function useUserLabel(orgId: string) {
  const members = useMembers(orgId);
  return (userId?: string | null) => {
    if (!userId) return "System";
    const member = members.data?.get(userId);
    return member?.displayName ?? member?.email ?? `User ${shortId(userId)}`;
  };
}

function Timestamp({ value }: { value: string }) {
  return (
    <SimpleTooltip label={formatDateTime(value)}>
      <span>{formatRelative(value)}</span>
    </SimpleTooltip>
  );
}

function DetailFields({
  orgId,
  module,
  capabilities,
  record,
  workflow,
}: {
  orgId: string;
  module: ModuleDefinition;
  capabilities: ModuleCapabilities;
  record: VersionedRecord;
  workflow: Workflow | null | undefined;
}) {
  const fields = visibleFields(module, capabilities);
  const tabs = resolveLayout(module, fields);
  const title = displayField(module);
  const sections = tabs.flatMap((tab) =>
    tab.sections.map((section) => ({ ...section, tabLabel: tabs.length > 1 ? tab.label : null }))
  );

  return (
    <div className="flex flex-col gap-6">
      {sections.map((section) => (
        <section key={`${section.tabLabel}-${section.key}`} className="rounded-lg border border-border bg-card">
          <header className="flex items-center gap-2 border-b border-border px-4 py-2.5">
            <h3 className="text-[13px] font-semibold">{section.label}</h3>
            {section.tabLabel && section.tabLabel !== section.label ? (
              <span className="text-xs text-muted-foreground">{section.tabLabel}</span>
            ) : null}
          </header>
          <dl className={cn("grid grid-cols-1 gap-x-6 gap-y-4 p-4", gridColumnsClass(section.columns))}>
            {section.fields.map((field) => (
              <div key={field.apiKey} className={cn("flex min-w-0 flex-col gap-1", isWideField(field) && "col-span-full")}>
                <dt className="text-xs text-muted-foreground">{field.label}</dt>
                <dd className={cn("min-w-0 text-[13px]", field.apiKey === title?.apiKey && "font-medium")}>
                  {workflow && field.apiKey === workflow.field && typeof record.data[field.apiKey] === "string" ? (
                    <WorkflowValue states={workflow.definition.states} value={String(record.data[field.apiKey])} />
                  ) : (
                    <FieldValue orgId={orgId} field={field} value={record.data[field.apiKey]} variant="detail" />
                  )}
                </dd>
              </div>
            ))}
          </dl>
        </section>
      ))}
    </div>
  );
}

export function RecordDetail({ module, recordId }: { module: ModuleDefinition; recordId: string }) {
  const router = useRouter();
  const { orgId, capabilities: capabilitiesFor } = useOrg();
  const capabilities = capabilitiesFor(module.apiKey);
  const query = useRecord(orgId, module.apiKey, recordId, { silent: true });
  const { deleteRecords, restoreRecord, deleting, restoring } = useRecordActions(orgId, module);
  const userLabel = useUserLabel(orgId);
  const relatedSources = useRelatedSources(module);
  const workflowQuery = useWorkflow(orgId, module.apiKey);
  const workflow = workflowQuery.data;
  const [confirmDelete, setConfirmDelete] = React.useState(false);

  if (query.isLoading) {
    return (
      <PageBody className="pt-6">
        <div className="flex items-center gap-3">
          <Skeleton className="size-10 rounded-lg" />
          <div className="flex flex-col gap-2">
            <Skeleton className="h-5 w-56" />
            <Skeleton className="h-3.5 w-32" />
          </div>
        </div>
        <div className="mt-8 grid gap-6 lg:grid-cols-[minmax(0,1fr)_17rem]">
          <Skeleton className="h-72 rounded-lg" />
          <Skeleton className="h-48 rounded-lg" />
        </div>
      </PageBody>
    );
  }

  if (query.isError || !query.data) {
    const notFound = query.error instanceof ApiError && query.error.isNotFound;
    return notFound ? (
      <EmptyState
        icon={SearchX}
        title={`This ${module.labelSingular.toLowerCase()} isn't available`}
        description="It may have been deleted, or it isn't shared with you."
        action={
          <Button variant="outline" asChild>
            <Link href={modulePath(orgId, module.apiKey)}>
              <ArrowLeft /> Back to {module.labelPlural.toLowerCase()}
            </Link>
          </Button>
        }
      />
    ) : (
      <ErrorState error={query.error} onRetry={() => void query.refetch()} />
    );
  }

  const record = query.data;
  const title = recordTitle(module, record);
  const deleted = Boolean(record.deletedAt);
  const stateValue = workflow ? record.data[workflow.field] : undefined;
  const currentState = typeof stateValue === "string" ? stateByKey(workflow, stateValue) : undefined;

  const copyId = async () => {
    await navigator.clipboard.writeText(record.id);
    toast.success("Record ID copied");
  };

  return (
    <>
      <div className="flex flex-col gap-4 px-4 pb-2 pt-6 sm:px-6 lg:px-8">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="flex min-w-0 items-center gap-3">
            <span className="grid size-10 shrink-0 place-items-center rounded-lg border border-border bg-surface text-muted-foreground">
              <ModuleGlyph moduleKey={module.apiKey} className="size-5" />
            </span>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <h1 className="truncate text-xl font-semibold tracking-[-0.025em]">
                  <RecordTitle orgId={orgId} module={module} record={record} />
                </h1>
                {currentState ? <StateBadge state={currentState} showCategory /> : null}
                {deleted ? <Badge variant="destructive">In trash</Badge> : null}
              </div>
              <p className="mt-0.5 text-[13px] text-muted-foreground">
                {module.labelSingular}, updated <Timestamp value={record.updatedAt} /> by {userLabel(record.updatedBy)}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {workflow && capabilities.update && !deleted ? (
              <WorkflowMoveMenu orgId={orgId} moduleKey={module.apiKey} record={record} workflow={workflow} subject={<RecordTitle orgId={orgId} module={module} record={record} />} />
            ) : null}
            {deleted ? (
              capabilities.update || capabilities.delete ? (
                <Button disabled={restoring} onClick={() => void restoreRecord(record)}>
                  <Undo2 /> Restore
                </Button>
              ) : null
            ) : capabilities.update ? (
              <Button asChild>
                <Link href={recordPath(orgId, module.apiKey, record.id, "/edit")}>
                  <Pencil /> Edit
                </Link>
              </Button>
            ) : null}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="icon" aria-label="More actions">
                  <MoreHorizontal />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem onSelect={() => void copyId()}>
                  <Copy /> Copy record ID
                </DropdownMenuItem>
                {capabilities.delete && !deleted ? (
                  <>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem variant="destructive" onSelect={() => setConfirmDelete(true)}>
                      <Trash2 /> Delete
                    </DropdownMenuItem>
                  </>
                ) : null}
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
        {deleted ? (
          <div className="rounded-lg border border-destructive/20 bg-destructive-soft px-4 py-2.5 text-[13px] text-destructive-soft-foreground">
            Deleted <Timestamp value={record.deletedAt!} />. It&apos;s hidden from lists until it&apos;s restored.
          </div>
        ) : null}
      </div>

      <PageBody className="pt-4">
        <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_17rem]">
          <Tabs defaultValue="details" className="min-w-0">
            <TabsList className="mb-5">
              <TabsTrigger value="details">Details</TabsTrigger>
              {relatedSources.map((source) => (
                <TabsTrigger key={source.key} value={source.key}>
                  {source.label}
                </TabsTrigger>
              ))}
              <TabsTrigger value="history">History</TabsTrigger>
            </TabsList>
            <TabsContent value="details">
              <DetailFields orgId={orgId} module={module} capabilities={capabilities} record={record} workflow={workflow} />
            </TabsContent>
            {relatedSources.map((source) => (
              <TabsContent key={source.key} value={source.key}>
                <RelatedRecords source={source} recordId={record.id} />
              </TabsContent>
            ))}
            <TabsContent value="history">
              <RecordHistory orgId={orgId} module={module} record={record} canRestore={capabilities.update} />
            </TabsContent>
          </Tabs>

          <div className="flex flex-col gap-4 lg:sticky lg:top-16">
          {workflow ? (
            <section aria-labelledby="stage-history-heading" className="rounded-lg border border-border bg-card p-4">
              <h2 id="stage-history-heading" className="mb-3 text-xs font-medium text-muted-foreground">
                Stage history
              </h2>
              <TransitionTimeline orgId={orgId} moduleKey={module.apiKey} recordId={record.id} workflow={workflow} />
            </section>
          ) : null}
          <aside aria-label="Record information" className="rounded-lg border border-border bg-card p-4">
            <dl className="flex flex-col gap-4">
              <MetaRow label="Created">
                <Timestamp value={record.createdAt} /> by {userLabel(record.createdBy)}
              </MetaRow>
              <MetaRow label="Last updated">
                <Timestamp value={record.updatedAt} /> by {userLabel(record.updatedBy)}
              </MetaRow>
              {record.ownerUserId ? <MetaRow label="Owner">{userLabel(record.ownerUserId)}</MetaRow> : null}
              <MetaRow label="Version">
                <span className="tabular">{record.version}</span>
              </MetaRow>
              <MetaRow label="Record ID">
                <button
                  type="button"
                  onClick={() => void copyId()}
                  className="focus-ring inline-flex max-w-full items-center gap-1.5 rounded-sm font-mono text-xs text-muted-foreground hover:text-foreground"
                >
                  <span className="truncate">{record.id}</span>
                  <Copy className="size-3 shrink-0" />
                </button>
              </MetaRow>
            </dl>
          </aside>
          </div>
        </div>
      </PageBody>

      <ConfirmDeleteDialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        module={module}
        count={1}
        title={title}
        pending={deleting}
        onConfirm={async () => {
          const [deletedRecord] = await deleteRecords([record]);
          if (deletedRecord) router.push(modulePath(orgId, module.apiKey));
        }}
      />
    </>
  );
}
