"use client";

import * as React from "react";
import Link from "next/link";
import { Check, Download, ExternalLink, FileText, ImageOff, Minus } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { downloadFile, errorMessage, useFileObjectUrl, useModule, useRecord } from "@/lib/api";
import { displayField, lookupTarget, optionLabel, recordTitle } from "@/lib/metadata/fields";
import {
  formatCurrency,
  formatDate,
  formatDateTime,
  formatDecimal,
  formatInteger,
  isCurrencyValue,
  isFileReference,
  jsonPreview,
  shortId,
} from "@/lib/metadata/format";
import { TONE_CLASSES, optionTone } from "@/lib/metadata/tones";
import { recordPath } from "@/lib/navigation";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import type { JsonValue } from "@/types/api";
import type { FieldDefinition, ModuleDefinition } from "@/types/metadata";
import type { FileReference, RecordItem } from "@/types/records";

export type ValueVariant = "cell" | "detail";

export function EmptyValue({ className }: { className?: string }) {
  return (
    <span className={cn("text-subtle-foreground", className)} aria-label="No value">
      –
    </span>
  );
}

export function SelectBadge({ field, value }: { field: FieldDefinition; value: string }) {
  return (
    <Badge variant="neutral" className={cn("max-w-48", TONE_CLASSES[optionTone(field, value)])}>
      {optionLabel(field, value)}
    </Badge>
  );
}

const stop = (event: React.SyntheticEvent) => event.stopPropagation();

/** Resolves a LOOKUP value (target record id) to the target's display label, with a short-id fallback. */
export function LookupValue({
  orgId,
  targetModuleKey,
  id,
  link = true,
}: {
  orgId: string;
  targetModuleKey: string;
  id: string;
  link?: boolean;
}) {
  const target = useModule(orgId, targetModuleKey);
  const record = useRecord(orgId, targetModuleKey, id, { silent: true });
  if (record.isLoading || target.isLoading) return <Skeleton className="inline-block h-3.5 w-20 align-middle" />;
  if (!record.data) {
    return (
      <SimpleTooltip label={record.error ? errorMessage(record.error) : "Record unavailable"}>
        <span className="font-mono text-xs text-muted-foreground">{shortId(id)}</span>
      </SimpleTooltip>
    );
  }
  const label = recordTitle(target.data, record.data);
  if (!link) return <span className="truncate">{label}</span>;
  return (
    <Link
      href={recordPath(orgId, targetModuleKey, id)}
      onClick={stop}
      className="focus-ring truncate rounded-sm text-foreground underline decoration-transparent underline-offset-[3px] transition-colors hover:decoration-current"
    >
      {label}
    </Link>
  );
}

/** Record title as text, resolving LOOKUP title fields (e.g. an application titled by its student) to the target. */
export function RecordTitle({
  orgId,
  module,
  record,
}: {
  orgId: string;
  module: ModuleDefinition | undefined;
  record: Pick<RecordItem, "id" | "data">;
}) {
  const field = module ? displayField(module) : null;
  const value = field ? record.data[field.apiKey] : undefined;
  const target = field?.type === "LOOKUP" ? lookupTarget(field) : null;
  if (target && typeof value === "string" && value) {
    return <LookupValue orgId={orgId} targetModuleKey={target} id={value} link={false} />;
  }
  return <>{recordTitle(module, record)}</>;
}

function ImageThumb({ orgId, file, variant }: { orgId: string; file: FileReference; variant: ValueVariant }) {
  const { url, isLoading, error } = useFileObjectUrl(orgId, file.fileId);
  const size = variant === "cell" ? "size-7 rounded-md" : "size-28 rounded-lg";
  if (isLoading) return <Skeleton className={size} />;
  if (error || !url) {
    return (
      <span className={cn("grid place-items-center border border-border bg-muted text-muted-foreground", size)}>
        <ImageOff className="size-3.5" />
      </span>
    );
  }
  // eslint-disable-next-line @next/next/no-img-element -- authenticated blob URL, not optimizable by next/image
  return <img src={url} alt={file.name ?? "Image"} className={cn("border border-border object-cover", size)} />;
}

function FileChip({ orgId, file, variant }: { orgId: string; file: FileReference; variant: ValueVariant }) {
  const [busy, setBusy] = React.useState(false);
  const download = async (event: React.MouseEvent) => {
    event.stopPropagation();
    setBusy(true);
    try {
      await downloadFile(orgId, file.fileId, file.name);
    } catch (error) {
      toast.error("Couldn't download the file", { description: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  };
  return (
    <button
      type="button"
      onClick={download}
      disabled={busy}
      className={cn(
        "focus-ring group inline-flex max-w-full items-center gap-1.5 rounded-md border border-border bg-background text-left transition-colors hover:border-border-strong hover:bg-accent disabled:opacity-60",
        variant === "cell" ? "h-6 px-1.5 text-xs" : "h-8 px-2.5 text-[13px]"
      )}
    >
      <FileText className="size-3.5 shrink-0 text-muted-foreground" />
      <span className="truncate">{file.name ?? shortId(file.fileId)}</span>
      <Download className="size-3.5 shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100" />
    </button>
  );
}

/** Read-only rendering of any field value (tables and detail views). */
export function FieldValue({
  orgId,
  field,
  value,
  variant = "cell",
}: {
  orgId: string;
  field: FieldDefinition;
  value: JsonValue | undefined;
  variant?: ValueVariant;
}) {
  if (value === undefined || value === null || value === "" || (Array.isArray(value) && value.length === 0)) {
    return <EmptyValue />;
  }

  switch (field.type) {
    case "TEXT":
      return <span className={cn(variant === "cell" && "block truncate")}>{String(value)}</span>;
    case "LONG_TEXT":
      return variant === "cell" ? (
        <span className="block max-w-80 truncate text-muted-foreground">{String(value)}</span>
      ) : (
        <p className="whitespace-pre-wrap leading-relaxed">{String(value)}</p>
      );
    case "EMAIL":
      return (
        <a href={`mailto:${value}`} onClick={stop} className="focus-ring block truncate rounded-sm hover:underline">
          {String(value)}
        </a>
      );
    case "PHONE":
      return (
        <a href={`tel:${String(value).replace(/\s/g, "")}`} onClick={stop} className="focus-ring rounded-sm tabular hover:underline">
          {String(value)}
        </a>
      );
    case "URL": {
      const text = String(value);
      let display = text;
      try {
        const url = new URL(text);
        display = `${url.host}${url.pathname === "/" ? "" : url.pathname}`;
      } catch {
        /* keep raw */
      }
      return (
        <a
          href={text}
          target="_blank"
          rel="noreferrer noopener"
          onClick={stop}
          className="focus-ring inline-flex max-w-full items-center gap-1 rounded-sm text-primary hover:underline"
        >
          <span className="truncate">{display}</span>
          <ExternalLink className="size-3 shrink-0" />
        </a>
      );
    }
    case "INTEGER":
      return <span className="tabular">{typeof value === "number" ? formatInteger(value) : String(value)}</span>;
    case "DECIMAL":
      return <span className="tabular">{formatDecimal(typeof value === "number" ? value : String(value))}</span>;
    case "CURRENCY":
      return isCurrencyValue(value) ? (
        <span className="tabular">{formatCurrency(value)}</span>
      ) : (
        <span className="text-muted-foreground">{jsonPreview(value)}</span>
      );
    case "BOOLEAN":
      return value === true ? (
        <span className="inline-flex items-center gap-1 text-success-soft-foreground">
          <Check className="size-3.5" strokeWidth={2.5} /> Yes
        </span>
      ) : (
        <span className="inline-flex items-center gap-1 text-muted-foreground">
          <Minus className="size-3.5" /> No
        </span>
      );
    case "DATE":
      return <span className="whitespace-nowrap tabular">{formatDate(String(value))}</span>;
    case "DATETIME":
      return <span className="whitespace-nowrap tabular">{formatDateTime(String(value))}</span>;
    case "SINGLE_SELECT":
      return <SelectBadge field={field} value={String(value)} />;
    case "MULTI_SELECT": {
      const values = Array.isArray(value) ? value.map(String) : [String(value)];
      const shown = variant === "cell" ? values.slice(0, 2) : values;
      return (
        <span className={cn("flex items-center gap-1", variant === "detail" && "flex-wrap")}>
          {shown.map((item) => (
            <SelectBadge key={item} field={field} value={item} />
          ))}
          {values.length > shown.length ? (
            <span className="text-xs text-muted-foreground">+{values.length - shown.length}</span>
          ) : null}
        </span>
      );
    }
    case "JSON":
      return variant === "cell" ? (
        <code className="block max-w-64 truncate font-mono text-xs text-muted-foreground">{jsonPreview(value, 48)}</code>
      ) : (
        <pre className="max-h-72 overflow-auto rounded-md border border-border bg-surface p-3 font-mono text-xs leading-relaxed">
          {JSON.stringify(value, null, 2)}
        </pre>
      );
    case "FILE_REFERENCE":
      return isFileReference(value) ? <FileChip orgId={orgId} file={value} variant={variant} /> : <EmptyValue />;
    case "IMAGE_REFERENCE":
      return isFileReference(value) ? <ImageThumb orgId={orgId} file={value} variant={variant} /> : <EmptyValue />;
    case "LOOKUP": {
      const target = lookupTarget(field);
      return target && typeof value === "string" ? (
        <LookupValue orgId={orgId} targetModuleKey={target} id={value} />
      ) : (
        <span className="font-mono text-xs">{String(value)}</span>
      );
    }
    default:
      return <span>{jsonPreview(value)}</span>;
  }
}
