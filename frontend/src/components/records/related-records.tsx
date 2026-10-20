"use client";

import * as React from "react";
import Link from "next/link";
import { Link2 } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { FieldValue } from "@/components/records/field-value";
import { ErrorState } from "@/components/common/error-state";
import { EmptyState } from "@/components/ui/empty-state";
import { Pagination } from "@/components/ui/pagination";
import { Skeleton } from "@/components/ui/skeleton";
import { useRecords, useRelated, useRelationships } from "@/lib/api";
import { activeFields, displayField, lookupTarget, recordTitle } from "@/lib/metadata/fields";
import { formatInteger } from "@/lib/metadata/format";
import { modulePath, recordPath } from "@/lib/navigation";
import type { FieldDefinition, ModuleDefinition } from "@/types/metadata";
import type { RecordData } from "@/types/records";

export type RelatedSource =
  | {
      kind: "relationship";
      key: string;
      label: string;
      relationship: string;
      direction: "outgoing" | "incoming";
      /** Module of the records listed. */
      moduleKey: string;
      /** Module of the record being viewed. */
      parentModuleKey: string;
    }
  | { kind: "lookup"; key: string; label: string; moduleKey: string; fieldKey: string };

/**
 * Related collections for a record: explicit relationships (both directions) and reverse lookups (records in other
 * modules whose filterable LOOKUP field points at this record, e.g. a student's applications).
 */
export function useRelatedSources(module: ModuleDefinition): RelatedSource[] {
  const { orgId, readableModules } = useOrg();
  const relationships = useRelationships(orgId);
  return React.useMemo(() => {
    const sources: RelatedSource[] = [];
    const readable = new Map(readableModules.map((m) => [m.apiKey, m]));
    for (const rel of relationships.data ?? []) {
      if (rel.status === "ARCHIVED") continue;
      if (rel.sourceModule === module.apiKey && readable.has(rel.targetModule)) {
        sources.push({
          kind: "relationship",
          key: `rel-${rel.apiKey}`,
          label: rel.label,
          relationship: rel.apiKey,
          direction: "outgoing",
          moduleKey: rel.targetModule,
          parentModuleKey: module.apiKey,
        });
      } else if (rel.targetModule === module.apiKey && readable.has(rel.sourceModule)) {
        sources.push({
          kind: "relationship",
          key: `rel-${rel.apiKey}-in`,
          label: rel.inverseLabel || readable.get(rel.sourceModule)!.labelPlural,
          relationship: rel.apiKey,
          direction: "incoming",
          moduleKey: rel.sourceModule,
          parentModuleKey: module.apiKey,
        });
      }
    }
    for (const other of readableModules) {
      for (const field of activeFields(other)) {
        if (field.type === "LOOKUP" && field.filterable && lookupTarget(field) === module.apiKey) {
          const ambiguous = activeFields(other).filter((f) => f.type === "LOOKUP" && lookupTarget(f) === module.apiKey).length > 1;
          sources.push({
            kind: "lookup",
            key: `lookup-${other.apiKey}-${field.apiKey}`,
            label: ambiguous ? `${other.labelPlural} (${field.label})` : other.labelPlural,
            moduleKey: other.apiKey,
            fieldKey: field.apiKey,
          });
        }
      }
    }
    return sources;
  }, [relationships.data, readableModules, module.apiKey]);
}

function previewFields(module: ModuleDefinition, exclude?: string) {
  const title = displayField(module);
  return activeFields(module)
    .filter((f) => f.apiKey !== title?.apiKey && f.apiKey !== exclude && !["LONG_TEXT", "JSON", "FILE_REFERENCE", "IMAGE_REFERENCE"].includes(f.type))
    .slice(0, 3);
}

function RelatedList({
  orgId,
  module,
  rows,
  columns,
}: {
  orgId: string;
  module: ModuleDefinition;
  rows: { id: string; data: RecordData }[];
  columns: FieldDefinition[];
}) {
  return (
    <div className="overflow-hidden rounded-lg border border-border">
      <ul className="divide-y divide-border">
        {rows.map((row) => (
          <li key={row.id}>
            <Link
              href={recordPath(orgId, module.apiKey, row.id)}
              className="focus-ring grid grid-cols-1 items-center gap-x-4 gap-y-1 px-4 py-2.5 text-[13px] transition-colors hover:bg-surface sm:grid-cols-[minmax(0,1.4fr)_repeat(3,minmax(0,1fr))]"
            >
              <span className="truncate font-medium">{recordTitle(module, row)}</span>
              {columns.map((field) => (
                <span key={field.apiKey} className="min-w-0 truncate text-muted-foreground">
                  <FieldValue orgId={orgId} field={field} value={row.data[field.apiKey]} />
                </span>
              ))}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

function LoadingRows() {
  return (
    <div className="flex flex-col gap-2">
      {Array.from({ length: 3 }, (_, i) => (
        <Skeleton key={i} className="h-10 w-full" />
      ))}
    </div>
  );
}

export function RelatedRecords({ source, recordId }: { source: RelatedSource; recordId: string }) {
  const { orgId, moduleByKey } = useOrg();
  const [page, setPage] = React.useState(0);
  const target = moduleByKey(source.moduleKey);
  if (!target) return null;
  return source.kind === "lookup" ? (
    <LookupRelated orgId={orgId} target={target} fieldKey={source.fieldKey} recordId={recordId} page={page} setPage={setPage} label={source.label} />
  ) : (
    <RelationshipRelated orgId={orgId} target={target} source={source} recordId={recordId} page={page} setPage={setPage} />
  );
}

function LookupRelated({
  orgId,
  target,
  fieldKey,
  recordId,
  page,
  setPage,
  label,
}: {
  orgId: string;
  target: ModuleDefinition;
  fieldKey: string;
  recordId: string;
  page: number;
  setPage: (page: number) => void;
  label: string;
}) {
  const query = useRecords(orgId, target.apiKey, { page, size: 10, filters: [{ field: fieldKey, op: "eq", value: recordId }] });
  if (query.isLoading) return <LoadingRows />;
  if (query.isError) return <ErrorState size="sm" error={query.error} onRetry={() => void query.refetch()} />;
  const items = query.data?.items ?? [];
  if (items.length === 0) return <EmptyState size="sm" icon={Link2} title={`No ${label.toLowerCase()} yet`} />;
  return (
    <div className="flex flex-col gap-3">
      <RelatedList orgId={orgId} module={target} rows={items} columns={previewFields(target, fieldKey)} />
      <RelatedFooter
        total={query.data?.totalElements ?? 0}
        page={page}
        totalPages={query.data?.totalPages ?? 0}
        setPage={setPage}
        href={`${modulePath(orgId, target.apiKey)}?${new URLSearchParams({ [`filter[${fieldKey}][eq]`]: recordId })}`}
      />
    </div>
  );
}

function RelationshipRelated({
  orgId,
  target,
  source,
  recordId,
  page,
  setPage,
}: {
  orgId: string;
  target: ModuleDefinition;
  source: Extract<RelatedSource, { kind: "relationship" }>;
  recordId: string;
  page: number;
  setPage: (page: number) => void;
}) {
  const query = useRelated(orgId, source.parentModuleKey, recordId, source.relationship, source.direction, page, 10);
  if (query.isLoading) return <LoadingRows />;
  if (query.isError) return <ErrorState size="sm" error={query.error} onRetry={() => void query.refetch()} />;
  const items = (query.data?.items ?? []).map((item) => ({ id: item.recordId, data: item.data }));
  if (items.length === 0) return <EmptyState size="sm" icon={Link2} title={`No linked ${source.label.toLowerCase()}`} />;
  return (
    <div className="flex flex-col gap-3">
      <RelatedList orgId={orgId} module={target} rows={items} columns={previewFields(target)} />
      <RelatedFooter total={query.data?.totalElements ?? 0} page={page} totalPages={query.data?.totalPages ?? 0} setPage={setPage} />
    </div>
  );
}

function RelatedFooter({
  total,
  page,
  totalPages,
  setPage,
  href,
}: {
  total: number;
  page: number;
  totalPages: number;
  setPage: (page: number) => void;
  href?: string;
}) {
  return (
    <div className="flex items-center justify-between text-xs text-muted-foreground">
      <span className="tabular">{formatInteger(total)} total</span>
      <div className="flex items-center gap-3">
        {href ? (
          <Link href={href} className="focus-ring rounded-sm font-medium text-primary hover:underline">
            Open as list
          </Link>
        ) : null}
        <Pagination page={page} totalPages={totalPages} onPageChange={setPage} />
      </div>
    </div>
  );
}
