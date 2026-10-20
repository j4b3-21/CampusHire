"use client";

import * as React from "react";
import Link from "next/link";
import { useDraggable } from "@dnd-kit/core";
import { GripVertical } from "lucide-react";

import { FieldValue, RecordTitle } from "@/components/records/field-value";
import { recordPath } from "@/lib/navigation";
import { cn } from "@/lib/utils";
import type { FieldDefinition, ModuleDefinition } from "@/types/metadata";
import type { RecordItem } from "@/types/records";

export interface CardDragData {
  record: RecordItem;
  state: string;
}

export interface BoardCardProps {
  orgId: string;
  module: ModuleDefinition;
  record: RecordItem;
  state: string;
  subtitleField: FieldDefinition | null;
  keyFields: FieldDefinition[];
  canDrag: boolean;
  /** Suppresses navigation for the click that ends a pointer drag. */
  recentlyDragged: () => boolean;
}

export function CardBody({
  orgId,
  module,
  record,
  subtitleField,
  keyFields,
  titleHref,
  onTitleClick,
}: Pick<BoardCardProps, "orgId" | "module" | "record" | "subtitleField" | "keyFields"> & {
  titleHref?: string;
  onTitleClick?: (event: React.MouseEvent) => void;
}) {
  const subtitle = subtitleField ? record.data[subtitleField.apiKey] : undefined;
  return (
    <div className="flex min-w-0 flex-1 flex-col gap-1.5">
      {titleHref ? (
        <Link
          href={titleHref}
          onClick={onTitleClick}
          className="focus-ring truncate rounded-sm text-[13px] font-medium leading-snug text-foreground hover:underline"
        >
          <RecordTitle orgId={orgId} module={module} record={record} />
        </Link>
      ) : (
        <span className="truncate text-[13px] font-medium leading-snug">
          <RecordTitle orgId={orgId} module={module} record={record} />
        </span>
      )}
      {subtitleField && subtitle !== undefined && subtitle !== null && subtitle !== "" ? (
        <div className="-mt-1 min-w-0 truncate text-xs text-muted-foreground [&_a]:text-muted-foreground">
          <FieldValue orgId={orgId} field={subtitleField} value={subtitle} />
        </div>
      ) : null}
      {keyFields.length > 0 ? (
        <dl className="mt-0.5 grid gap-1">
          {keyFields.map((field) => {
            const value = record.data[field.apiKey];
            if (value === undefined || value === null || value === "") return null;
            return (
              <div key={field.apiKey} className="flex min-w-0 items-center gap-2 text-xs">
                <dt className="w-20 shrink-0 truncate text-subtle-foreground">{field.label}</dt>
                <dd className="min-w-0 flex-1 truncate">
                  <FieldValue orgId={orgId} field={field} value={value} />
                </dd>
              </div>
            );
          })}
        </dl>
      ) : null}
    </div>
  );
}

/**
 * A draggable record card. The grip is the keyboard drag handle (space/enter to pick up, arrows to choose a stage);
 * pointer users can drag the whole card. The title links to the record.
 */
export function BoardCard({ orgId, module, record, state, subtitleField, keyFields, canDrag, recentlyDragged }: BoardCardProps) {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, isDragging } = useDraggable({
    id: record.id,
    data: { record, state } satisfies CardDragData,
    disabled: !canDrag,
  });

  return (
    <li
      ref={setNodeRef}
      onPointerDown={(event) => listeners?.onPointerDown?.(event)}
      className={cn(
        "group/card flex items-start gap-1.5 rounded-lg border border-border bg-card p-2.5 shadow-xs transition-[border-color,box-shadow,opacity]",
        canDrag && "cursor-grab active:cursor-grabbing hover:border-border-strong",
        isDragging && "opacity-40"
      )}
    >
      <CardBody
        orgId={orgId}
        module={module}
        record={record}
        subtitleField={subtitleField}
        keyFields={keyFields}
        titleHref={recordPath(orgId, module.apiKey, record.id)}
        onTitleClick={(event) => {
          if (recentlyDragged()) event.preventDefault();
        }}
      />
      {canDrag ? (
        <button
          type="button"
          ref={setActivatorNodeRef}
          {...attributes}
          onKeyDown={(event) => listeners?.onKeyDown?.(event)}
          aria-label="Move card"
          className="focus-ring -mr-1 -mt-0.5 shrink-0 rounded-sm p-0.5 text-subtle-foreground opacity-60 transition-opacity hover:text-foreground group-hover/card:opacity-100 focus-visible:opacity-100"
        >
          <GripVertical className="size-4" />
        </button>
      ) : null}
    </li>
  );
}
