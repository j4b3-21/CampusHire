"use client";

import * as React from "react";
import { ArrowRight } from "lucide-react";

import { StateBadge } from "@/components/workflow/state-badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import type { WorkflowState } from "@/types/workflow";

export interface TransitionDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  /** Target state shown in the description. */
  target?: WorkflowState;
  subject?: React.ReactNode;
  note?: React.ReactNode;
  requireComment: boolean;
  pending?: boolean;
  confirmLabel?: string;
  onConfirm: (comment: string) => void | Promise<void>;
}

/** Asks for the transition comment (required or optional) before moving a record. */
export function TransitionDialog({
  open,
  onOpenChange,
  title,
  target,
  subject,
  note,
  requireComment,
  pending,
  confirmLabel = "Move",
  onConfirm,
}: TransitionDialogProps) {
  const [comment, setComment] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const id = React.useId();
  const missing = requireComment && !comment.trim();

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setTouched(true);
    if (missing) return;
    await onConfirm(comment.trim());
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          setComment("");
          setTouched(false);
        }
        onOpenChange(next);
      }}
    >
      <DialogContent>
        <form onSubmit={submit} className="grid gap-4">
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            <DialogDescription className="flex flex-wrap items-center gap-1.5">
              {subject ? <span className="font-medium text-foreground">{subject}</span> : null}
              {target ? (
                <>
                  <ArrowRight className="size-3.5" aria-label="moves to" />
                  <StateBadge state={target} />
                </>
              ) : null}
            </DialogDescription>
            {note ? <p className="text-[13px] leading-relaxed text-muted-foreground">{note}</p> : null}
          </DialogHeader>
          <div className="grid gap-2">
            <Label htmlFor={`${id}-comment`}>
              Comment {requireComment ? <span className="text-destructive">*</span> : <span className="font-normal text-muted-foreground">(optional)</span>}
            </Label>
            <Textarea
              id={`${id}-comment`}
              autoFocus
              value={comment}
              maxLength={2000}
              onChange={(event) => setComment(event.target.value)}
              placeholder={requireComment ? "Explain why this record is moving" : "Add context for the history"}
              aria-invalid={(touched && missing) || undefined}
              aria-describedby={touched && missing ? `${id}-error` : undefined}
            />
            {touched && missing ? (
              <p id={`${id}-error`} className="text-xs text-destructive">
                This step needs a comment.
              </p>
            ) : null}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => onOpenChange(false)} disabled={pending}>
              Cancel
            </Button>
            <Button type="submit" disabled={pending}>
              {pending ? <Spinner className="text-primary-foreground" /> : null}
              {confirmLabel}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
