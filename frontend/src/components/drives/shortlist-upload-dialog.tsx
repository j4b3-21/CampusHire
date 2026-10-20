"use client";

import * as React from "react";
import { FileSpreadsheet, UploadCloud, X } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { StateBadge } from "@/components/workflow/state-badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage, useShortlistUpload } from "@/lib/api";
import { formatBytes } from "@/lib/metadata/format";
import { cn } from "@/lib/utils";
import type { PipelineResult } from "@/types/campushire";
import type { Workflow, WorkflowState } from "@/types/workflow";

const ACCEPT = ".xlsx,.xls,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel,text/csv";
const MAX_BYTES = 25 * 1024 * 1024;

/** Moves applicants listed by roll number in an Excel/CSV file to one stage. */
export function ShortlistUploadDialog({
  open,
  onOpenChange,
  driveId,
  workflow,
  targets,
  onResult,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  driveId: string;
  workflow: Workflow;
  targets: WorkflowState[];
  onResult: (result: PipelineResult, target: WorkflowState) => void;
}) {
  const { orgId } = useOrg();
  const upload = useShortlistUpload(orgId, driveId);
  const inputRef = React.useRef<HTMLInputElement>(null);
  const [file, setFile] = React.useState<File | null>(null);
  const [to, setTo] = React.useState<string>(targets.find((state) => state.key === "shortlisted")?.key ?? targets[0]?.key ?? "");
  const [comment, setComment] = React.useState("");
  const [error, setError] = React.useState<string | null>(null);
  const [dragging, setDragging] = React.useState(false);
  const target = targets.find((state) => state.key === to);
  const requireComment = workflow.definition.transitions.some((transition) => transition.to === to && transition.requireComment);

  const reset = () => {
    setFile(null);
    setComment("");
    setError(null);
  };

  const pick = (candidate: File | undefined) => {
    if (!candidate) return;
    setError(null);
    if (!/\.(xlsx|xls|csv)$/i.test(candidate.name)) {
      setError("Choose an Excel (.xlsx, .xls) or CSV file.");
      return;
    }
    if (candidate.size > MAX_BYTES) {
      setError(`The file is larger than ${formatBytes(MAX_BYTES)}.`);
      return;
    }
    setFile(candidate);
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!file || !target) return;
    if (requireComment && !comment.trim()) {
      setError("This stage needs a comment.");
      return;
    }
    try {
      const result = await upload.mutateAsync({ file, to: target.key, comment });
      reset();
      onOpenChange(false);
      onResult(result, target);
    } catch (cause) {
      setError(errorMessage(cause));
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) reset();
        onOpenChange(next);
      }}
    >
      <DialogContent className="max-w-lg">
        <form onSubmit={submit} className="grid gap-4">
          <DialogHeader>
            <DialogTitle>Upload shortlist</DialogTitle>
            <DialogDescription>
              An Excel or CSV file with a roll number column (“Roll no.”, “Roll number”, “Register number”…) in the first rows. Every listed
              applicant moves to the stage you pick.
            </DialogDescription>
          </DialogHeader>

          <input
            ref={inputRef}
            type="file"
            accept={ACCEPT}
            className="sr-only"
            aria-label="Shortlist file"
            onChange={(event) => {
              pick(event.target.files?.[0]);
              event.target.value = "";
            }}
          />
          {file ? (
            <div className="flex items-center gap-3 rounded-md border border-border bg-surface px-3 py-2.5">
              <FileSpreadsheet className="size-5 shrink-0 text-success" />
              <div className="min-w-0 flex-1">
                <p className="truncate text-[13px] font-medium">{file.name}</p>
                <p className="text-xs text-muted-foreground">{formatBytes(file.size)}</p>
              </div>
              <Button variant="ghost" size="icon-sm" aria-label="Remove file" onClick={() => setFile(null)}>
                <X />
              </Button>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => inputRef.current?.click()}
              onDragOver={(event) => {
                event.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={(event) => {
                event.preventDefault();
                setDragging(false);
                pick(event.dataTransfer.files?.[0]);
              }}
              className={cn(
                "focus-ring flex flex-col items-center gap-2 rounded-lg border border-dashed border-border-strong px-4 py-6 text-center transition-colors hover:bg-surface",
                dragging && "border-primary bg-primary-soft/40"
              )}
            >
              <UploadCloud className="size-6 text-muted-foreground" />
              <span className="text-[13px] font-medium">Drop the file here or click to browse</span>
              <span className="text-xs text-muted-foreground">XLSX, XLS or CSV · up to 25 MB · 5,000 rows</span>
            </button>
          )}

          <div className="grid gap-2">
            <Label htmlFor="shortlist-stage">Move listed applicants to</Label>
            <Select value={to} onValueChange={setTo}>
              <SelectTrigger id="shortlist-stage">
                <SelectValue placeholder="Choose a stage" />
              </SelectTrigger>
              <SelectContent>
                {targets.map((state) => (
                  <SelectItem key={state.key} value={state.key}>
                    <StateBadge state={state} />
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="grid gap-2">
            <Label htmlFor="shortlist-comment">
              Comment {requireComment ? <span className="text-destructive">*</span> : <span className="font-normal text-muted-foreground">(optional)</span>}
            </Label>
            <Textarea
              id="shortlist-comment"
              value={comment}
              maxLength={2000}
              onChange={(event) => setComment(event.target.value)}
              placeholder="e.g. Cleared the online assessment"
            />
          </div>
          {error ? (
            <p role="alert" className="text-xs text-destructive">
              {error}
            </p>
          ) : null}
          <DialogFooter>
            <Button variant="outline" onClick={() => onOpenChange(false)} disabled={upload.isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={!file || !target || upload.isPending}>
              {upload.isPending ? <Spinner className="text-primary-foreground" /> : null}
              Upload and move
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
