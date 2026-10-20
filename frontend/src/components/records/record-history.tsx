"use client";

import * as React from "react";
import { History, RotateCcw } from "lucide-react";
import { toast } from "sonner";

import { EmptyValue, FieldValue } from "@/components/records/field-value";
import { ErrorState } from "@/components/common/error-state";
import { Avatar, AvatarFallback, initials } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { useMembers, useRecordVersions, useRevertRecordVersion, type VersionedRecord } from "@/lib/api";
import { activeFields } from "@/lib/metadata/fields";
import { formatDateTime, formatRelative, humanize, shortId } from "@/lib/metadata/format";
import { cn } from "@/lib/utils";
import type { JsonValue } from "@/types/api";
import type { FieldDefinition, ModuleDefinition } from "@/types/metadata";
import type { RecordData, RecordVersion } from "@/types/records";

interface Change {
  key: string;
  field?: FieldDefinition;
  before: JsonValue | undefined;
  after: JsonValue | undefined;
}

function diff(before: RecordData | undefined, after: RecordData, fields: Map<string, FieldDefinition>): Change[] {
  const keys = new Set([...Object.keys(before ?? {}), ...Object.keys(after)]);
  const changes: Change[] = [];
  for (const key of keys) {
    const a = before?.[key];
    const b = after[key];
    if (JSON.stringify(a) !== JSON.stringify(b)) changes.push({ key, field: fields.get(key), before: a, after: b });
  }
  return changes.sort((x, y) => (x.field?.position ?? 999) - (y.field?.position ?? 999));
}

const CHANGE_LABELS: Record<string, string> = {
  CREATE: "created this record",
  UPDATE: "made changes",
  DELETE: "deleted this record",
  RESTORE: "restored this record",
  REVERT: "restored an earlier version",
};

function ValueCell({ orgId, change, side }: { orgId: string; change: Change; side: "before" | "after" }) {
  const value = side === "before" ? change.before : change.after;
  if (!change.field) {
    return value === undefined ? <EmptyValue /> : <code className="font-mono text-xs">{JSON.stringify(value)}</code>;
  }
  return <FieldValue orgId={orgId} field={change.field} value={value} />;
}

export function RecordHistory({
  orgId,
  module,
  record,
  canRestore,
}: {
  orgId: string;
  module: ModuleDefinition;
  record: VersionedRecord;
  canRestore: boolean;
}) {
  const versions = useRecordVersions(orgId, module.apiKey, record.id);
  const members = useMembers(orgId);
  const revert = useRevertRecordVersion(orgId, module.apiKey);
  const fieldMap = React.useMemo(() => new Map(activeFields(module).map((f) => [f.apiKey, f])), [module]);

  const entries = React.useMemo(() => {
    const items = [...(versions.data?.items ?? [])].sort((a, b) => a.version - b.version);
    return items
      .map((version, index) => ({ version, changes: diff(items[index - 1]?.data, version.data, fieldMap), previous: items[index - 1] }))
      .reverse();
  }, [versions.data, fieldMap]);

  if (versions.isLoading) {
    return (
      <div className="flex flex-col gap-5">
        {Array.from({ length: 3 }, (_, i) => (
          <div key={i} className="flex gap-3">
            <Skeleton className="size-7 rounded-full" />
            <div className="flex flex-1 flex-col gap-2">
              <Skeleton className="h-3.5 w-48" />
              <Skeleton className="h-12 w-full" />
            </div>
          </div>
        ))}
      </div>
    );
  }
  if (versions.isError) return <ErrorState size="sm" error={versions.error} onRetry={() => void versions.refetch()} />;
  if (entries.length === 0) return <EmptyState size="sm" icon={History} title="No history yet" />;

  const actorName = (version: RecordVersion) => {
    const member = version.changedBy ? members.data?.get(version.changedBy) : undefined;
    return member?.displayName ?? member?.email ?? (version.changedBy ? `User ${shortId(version.changedBy)}` : "System");
  };

  const restoreVersion = async (version: number) => {
    await revert.mutateAsync({ id: record.id, version, ifMatch: record.etag });
    toast.success(`Restored the values from version ${version}`);
  };

  return (
    <ol className="relative flex flex-col gap-6 before:absolute before:bottom-2 before:left-[13px] before:top-2 before:w-px before:bg-border">
      {entries.map(({ version, changes }) => {
        const name = actorName(version);
        const isCurrent = version.version === record.version;
        const showChanges = version.changeType !== "DELETE" && version.changeType !== "RESTORE";
        const visible = version.changeType === "CREATE" ? changes.filter((c) => c.after !== undefined) : changes;
        return (
          <li key={version.version} className="relative flex gap-3">
            <Avatar className="relative z-[1] size-7 ring-4 ring-background">
              <AvatarFallback className="text-[10px]">{initials(name)}</AvatarFallback>
            </Avatar>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[13px]">
                <span className="font-medium">{name}</span>
                <span className="text-muted-foreground">
                  {CHANGE_LABELS[version.changeType] ?? humanize(version.changeType.toLowerCase())}
                </span>
                <SimpleTooltip label={formatDateTime(version.changedAt)}>
                  <span className="text-xs text-subtle-foreground">{formatRelative(version.changedAt)}</span>
                </SimpleTooltip>
                <span className="text-xs text-subtle-foreground tabular">v{version.version}</span>
                {!isCurrent && canRestore && !record.deletedAt && version.changeType !== "DELETE" ? (
                  <Button
                    variant="ghost"
                    size="xs"
                    className="ml-auto"
                    disabled={revert.isPending}
                    onClick={() => void restoreVersion(version.version)}
                  >
                    <RotateCcw /> Restore this version
                  </Button>
                ) : null}
              </div>
              {showChanges && visible.length > 0 ? (
                <div className="mt-2 overflow-hidden rounded-lg border border-border">
                  <table className="w-full text-[13px]">
                    <tbody>
                      {visible.map((change) => (
                        <tr key={change.key} className="border-b border-border last:border-b-0">
                          <th
                            scope="row"
                            className="w-36 bg-surface px-3 py-2 text-left align-top text-xs font-medium text-muted-foreground"
                          >
                            {change.field?.label ?? humanize(change.key)}
                          </th>
                          {version.changeType === "CREATE" ? (
                            <td className="px-3 py-2 align-top">
                              <ValueCell orgId={orgId} change={change} side="after" />
                            </td>
                          ) : (
                            <td className="px-3 py-2 align-top">
                              <div className="flex flex-wrap items-center gap-2">
                                <span className={cn("max-w-full opacity-60", change.before !== undefined && "line-through decoration-border-strong")}>
                                  <ValueCell orgId={orgId} change={change} side="before" />
                                </span>
                                <span className="text-subtle-foreground" aria-label="changed to">
                                  →
                                </span>
                                <span className="max-w-full">
                                  <ValueCell orgId={orgId} change={change} side="after" />
                                </span>
                              </div>
                            </td>
                          )}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
