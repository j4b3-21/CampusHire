"use client";

import * as React from "react";
import { FileText, ImageIcon, RefreshCw, Trash2, UploadCloud } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { errorMessage, useFileObjectUrl, useUploadFile } from "@/lib/api";
import { formatBytes, shortId } from "@/lib/metadata/format";
import { cn } from "@/lib/utils";
import type { FileReference } from "@/types/records";

const MAX_BYTES = 25 * 1024 * 1024;

export interface FileInputProps {
  id?: string;
  orgId: string;
  value: FileReference | null;
  onChange: (value: FileReference | null) => void;
  onBlur?: () => void;
  image?: boolean;
  disabled?: boolean;
  invalid?: boolean;
}

function Preview({ orgId, file }: { orgId: string; file: FileReference }) {
  const { url, isLoading } = useFileObjectUrl(orgId, file.fileId);
  if (isLoading) return <Skeleton className="size-14 rounded-md" />;
  if (!url) {
    return (
      <span className="grid size-14 place-items-center rounded-md border border-border bg-muted text-muted-foreground">
        <ImageIcon className="size-5" />
      </span>
    );
  }
  // eslint-disable-next-line @next/next/no-img-element -- authenticated blob URL
  return <img src={url} alt={file.name ?? "Uploaded image"} className="size-14 rounded-md border border-border object-cover" />;
}

/** Uploads to `POST /orgs/{orgId}/files` and stores `{fileId, name, contentType}`. */
export function FileInput({ id, orgId, value, onChange, onBlur, image, disabled, invalid }: FileInputProps) {
  const inputRef = React.useRef<HTMLInputElement>(null);
  const { upload, progress, isUploading } = useUploadFile(orgId);
  const [error, setError] = React.useState<string | null>(null);
  const [dragging, setDragging] = React.useState(false);
  const [localName, setLocalName] = React.useState<string | null>(null);

  const handleFile = async (file: File | undefined) => {
    if (!file) return;
    setError(null);
    if (image && !file.type.startsWith("image/")) {
      setError("Choose an image file (PNG, JPG, WebP).");
      return;
    }
    if (file.size > MAX_BYTES) {
      setError(`Files can be at most ${formatBytes(MAX_BYTES)}.`);
      return;
    }
    setLocalName(file.name);
    try {
      const uploaded = await upload(file);
      onChange({ fileId: uploaded.id, name: uploaded.name ?? file.name, contentType: uploaded.contentType ?? file.type });
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setLocalName(null);
      onBlur?.();
    }
  };

  const pick = () => inputRef.current?.click();

  return (
    <div className="flex flex-col gap-2">
      <input
        ref={inputRef}
        id={id}
        type="file"
        className="sr-only"
        accept={image ? "image/*" : undefined}
        disabled={disabled || isUploading}
        onChange={(event) => {
          void handleFile(event.target.files?.[0]);
          event.target.value = "";
        }}
      />
      {isUploading ? (
        <div className="flex flex-col gap-2 rounded-md border border-border bg-surface px-3 py-2.5">
          <div className="flex items-center justify-between text-[13px]">
            <span className="truncate">{localName}</span>
            <span className="text-xs text-muted-foreground tabular">{progress}%</span>
          </div>
          <Progress value={progress} aria-label="Upload progress" />
        </div>
      ) : value ? (
        <div className="flex items-center gap-3 rounded-md border border-border bg-background px-3 py-2">
          {image ? (
            <Preview orgId={orgId} file={value} />
          ) : (
            <span className="grid size-8 place-items-center rounded-md bg-muted text-muted-foreground">
              <FileText className="size-4" />
            </span>
          )}
          <div className="min-w-0 flex-1">
            <p className="truncate text-[13px] font-medium">{value.name ?? shortId(value.fileId)}</p>
            {value.contentType ? <p className="truncate text-xs text-muted-foreground">{value.contentType}</p> : null}
          </div>
          {!disabled ? (
            <div className="flex items-center gap-1">
              <Button variant="ghost" size="icon-sm" aria-label="Replace file" onClick={pick}>
                <RefreshCw />
              </Button>
              <Button variant="ghost" size="icon-sm" aria-label="Remove file" onClick={() => onChange(null)}>
                <Trash2 />
              </Button>
            </div>
          ) : null}
        </div>
      ) : (
        <button
          type="button"
          disabled={disabled}
          onClick={pick}
          onDragOver={(event) => {
            event.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(event) => {
            event.preventDefault();
            setDragging(false);
            void handleFile(event.dataTransfer.files?.[0]);
          }}
          data-invalid={invalid || undefined}
          className={cn(
            "focus-ring flex items-center gap-3 rounded-md border border-dashed border-border-strong bg-background px-3 py-3 text-left transition-colors hover:border-muted-foreground/50 hover:bg-surface disabled:cursor-not-allowed disabled:opacity-60 data-[invalid=true]:border-destructive",
            dragging && "border-primary bg-primary-soft/40"
          )}
        >
          <span className="grid size-8 shrink-0 place-items-center rounded-md bg-muted text-muted-foreground">
            {image ? <ImageIcon className="size-4" /> : <UploadCloud className="size-4" />}
          </span>
          <span className="text-[13px]">
            <span className="font-medium">{image ? "Upload an image" : "Upload a file"}</span>
            <span className="block text-xs text-muted-foreground">Drop it here or click to browse. Up to 25 MB.</span>
          </span>
        </button>
      )}
      {error ? <p className="text-xs text-destructive">{error}</p> : null}
    </div>
  );
}
