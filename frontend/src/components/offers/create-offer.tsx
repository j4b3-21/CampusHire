"use client";

import * as React from "react";
import Link from "next/link";
import { toast } from "sonner";
import { BadgeCheck, FileUp } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";
import { ApiError, useCreateOffer, useRecords, useUploadFile, type CreateOfferInput, type VersionedRecord } from "@/lib/api";

const OFFER_STAGES = new Set(["shortlisted", "assessment", "interview", "offered"]);

const OFFER_TYPES: { value: CreateOfferInput["offerType"]; label: string }[] = [
  { value: "full_time", label: "Full time" },
  { value: "internship", label: "Internship" },
  { value: "ppo", label: "Pre-placement offer" },
];

type FieldErrors = Partial<Record<"ctc" | "joiningDate" | "respondBy" | "offerLetterFileId", string>>;

function fieldErrors(error: ApiError): FieldErrors {
  const result: FieldErrors = {};
  for (const violation of error.errors) {
    const key = violation.field.replace(/^data\./, "") as keyof FieldErrors;
    if (key === "ctc" || key === "joiningDate" || key === "respondBy" || key === "offerLetterFileId") {
      result[key] = violation.message;
    }
  }
  return result;
}

/** Records an offer for an application (placement officers); the backend applies the institute's offer policy. */
export function CreateOfferPanel({ record }: { record: VersionedRecord }) {
  const { orgId, isAdmin, capabilities } = useOrg();
  const [open, setOpen] = React.useState(false);
  const stage = typeof record.data.stage === "string" ? record.data.stage : null;
  const eligible = capabilities("offer").create && stage !== null && OFFER_STAGES.has(stage);
  const existing = useRecords(
    orgId,
    "offer",
    { size: 1, filters: [{ field: "application", op: "eq", value: record.id }] },
    { enabled: eligible }
  );
  if (!eligible) return null;
  const offer = existing.data?.items[0];
  if (existing.isLoading) return null;
  if (offer) {
    return (
      <section className="rounded-lg border border-border bg-card p-4">
        <div className="flex items-center gap-3">
          <span className="grid size-8 shrink-0 place-items-center rounded-md bg-success-soft text-success-soft-foreground">
            <BadgeCheck className="size-4" />
          </span>
          <div className="min-w-0 flex-1">
            <h3 className="text-[13px] font-semibold">Offer recorded</h3>
            <p className="text-xs text-muted-foreground">This application already has an offer.</p>
          </div>
          <Button variant="outline" size="sm" asChild>
            <Link href={`/app/${orgId}/m/offer/${offer.id}`}>View offer</Link>
          </Button>
        </div>
      </section>
    );
  }

  return (
    <section className="rounded-lg border border-border bg-card p-4">
      <div className="flex items-start gap-3">
        <span className="grid size-8 shrink-0 place-items-center rounded-md bg-success-soft text-success-soft-foreground">
          <BadgeCheck className="size-4" />
        </span>
        <div className="min-w-0 flex-1">
          <h3 className="text-[13px] font-semibold">Offer</h3>
          <p className="text-xs text-muted-foreground">Record the company&apos;s offer; the student is notified and can accept it.</p>
        </div>
      </div>
      <Button size="sm" className="mt-3 w-full" onClick={() => setOpen(true)}>
        Create offer
      </Button>
      <CreateOfferDialog orgId={orgId} applicationId={record.id} isAdmin={isAdmin} open={open} onOpenChange={setOpen} />
    </section>
  );
}

function CreateOfferDialog({
  orgId,
  applicationId,
  isAdmin,
  open,
  onOpenChange,
}: {
  orgId: string;
  applicationId: string;
  isAdmin: boolean;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const id = React.useId();
  const createOffer = useCreateOffer(orgId);
  const { upload, isUploading, progress } = useUploadFile(orgId);
  const [ctcLpa, setCtcLpa] = React.useState("");
  const [offerType, setOfferType] = React.useState<CreateOfferInput["offerType"]>("full_time");
  const [joiningDate, setJoiningDate] = React.useState("");
  const [respondBy, setRespondBy] = React.useState("");
  const [letter, setLetter] = React.useState<{ id: string; name: string } | null>(null);
  const [override, setOverride] = React.useState(false);
  const [errors, setErrors] = React.useState<FieldErrors>({});
  const [formError, setFormError] = React.useState<string | null>(null);
  const [policyBlocked, setPolicyBlocked] = React.useState(false);

  const reset = () => {
    setCtcLpa("");
    setOfferType("full_time");
    setJoiningDate("");
    setRespondBy("");
    setLetter(null);
    setOverride(false);
    setErrors({});
    setFormError(null);
    setPolicyBlocked(false);
  };

  const onLetter = async (file: File | undefined) => {
    if (!file) return;
    try {
      const stored = await upload(file);
      setLetter({ id: stored.id, name: stored.name });
      setErrors((current) => ({ ...current, offerLetterFileId: undefined }));
    } catch (error) {
      setErrors((current) => ({
        ...current,
        offerLetterFileId: error instanceof ApiError ? error.detail ?? error.message : "Upload failed",
      }));
    }
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const lpa = Number(ctcLpa);
    if (!ctcLpa.trim() || !Number.isFinite(lpa) || lpa <= 0) {
      setErrors({ ctc: "Enter the CTC in LPA, for example 12.5" });
      return;
    }
    setErrors({});
    setFormError(null);
    try {
      const offer = await createOffer.mutateAsync({
        applicationId,
        ctc: Math.round(lpa * 100_000),
        offerType,
        joiningDate: joiningDate || undefined,
        respondBy: respondBy || undefined,
        offerLetterFileId: letter?.id,
        override: override || undefined,
      });
      toast.success("Offer created", {
        description: "The student has been notified.",
        action: { label: "View", onClick: () => window.location.assign(`/app/${orgId}/m/offer/${offer.id}`) },
      });
      reset();
      onOpenChange(false);
    } catch (error) {
      if (!(error instanceof ApiError)) {
        setFormError("The offer could not be created. Try again.");
      } else if (error.code === "offer_policy_violation") {
        setPolicyBlocked(true);
        setFormError(error.detail ?? "This offer breaks the institute's placement policy.");
      } else if (error.isValidation) {
        setErrors(fieldErrors(error));
        setFormError(error.errors.length ? null : error.detail ?? error.message);
      } else if (error.code === "unique_violation") {
        setFormError("This application already has an offer.");
      } else {
        setFormError(error.detail ?? error.message);
      }
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
      <DialogContent>
        <form onSubmit={submit} className="grid gap-4" noValidate>
          <DialogHeader>
            <DialogTitle>Create offer</DialogTitle>
            <DialogDescription>
              The application moves to Offered and the student becomes placed. Offer limits and dream upgrades follow
              the{" "}
              <Link href={`/app/${orgId}/admin/settings`} className="text-primary hover:underline">
                placement policy
              </Link>
              .
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-ctc`}>
                CTC (LPA) <span className="text-destructive">*</span>
              </Label>
              <Input
                id={`${id}-ctc`}
                inputMode="decimal"
                autoFocus
                placeholder="12.5"
                value={ctcLpa}
                onChange={(event) => setCtcLpa(event.target.value)}
                aria-invalid={Boolean(errors.ctc) || undefined}
              />
              {errors.ctc ? <p className="text-xs text-destructive">{errors.ctc}</p> : null}
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-type`}>Type</Label>
              <Select value={offerType} onValueChange={(value) => setOfferType(value as CreateOfferInput["offerType"])}>
                <SelectTrigger id={`${id}-type`}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {OFFER_TYPES.map((type) => (
                    <SelectItem key={type.value} value={type.value}>
                      {type.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-joining`}>Joining date</Label>
              <Input id={`${id}-joining`} type="date" value={joiningDate} onChange={(event) => setJoiningDate(event.target.value)} aria-invalid={Boolean(errors.joiningDate) || undefined} />
              {errors.joiningDate ? <p className="text-xs text-destructive">{errors.joiningDate}</p> : null}
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-respond`}>Respond by</Label>
              <Input id={`${id}-respond`} type="date" value={respondBy} onChange={(event) => setRespondBy(event.target.value)} aria-invalid={Boolean(errors.respondBy) || undefined} />
              {errors.respondBy ? <p className="text-xs text-destructive">{errors.respondBy}</p> : null}
            </div>
          </div>

          <div className="grid gap-1.5">
            <Label htmlFor={`${id}-letter`}>Offer letter (PDF)</Label>
            <label
              htmlFor={`${id}-letter`}
              className="focus-ring flex cursor-pointer items-center gap-3 rounded-md border border-dashed border-border-strong px-3 py-2.5 text-[13px] transition-colors hover:bg-surface"
            >
              {isUploading ? <Spinner className="size-4" /> : <FileUp className="size-4 text-muted-foreground" />}
              <span className="min-w-0 flex-1 truncate">
                {isUploading ? `Uploading… ${Math.round(progress * 100)}%` : letter ? letter.name : "Choose a file (optional)"}
              </span>
            </label>
            <input
              id={`${id}-letter`}
              type="file"
              accept="application/pdf"
              className="sr-only"
              onChange={(event) => void onLetter(event.target.files?.[0])}
            />
            {errors.offerLetterFileId ? <p className="text-xs text-destructive">{errors.offerLetterFileId}</p> : null}
          </div>

          {policyBlocked && isAdmin ? (
            <label className="flex items-start gap-2.5 rounded-md border border-warning/30 bg-warning-soft px-3 py-2.5 text-[13px] text-warning-soft-foreground">
              <Checkbox checked={override} onCheckedChange={(checked) => setOverride(checked === true)} className="mt-0.5" />
              <span>Override the placement policy for this offer. The override is recorded in the audit log.</span>
            </label>
          ) : null}

          {formError ? (
            <p role="alert" className="rounded-md border border-destructive/20 bg-destructive-soft px-3 py-2 text-[13px] text-destructive-soft-foreground">
              {formError}
            </p>
          ) : null}

          <DialogFooter>
            <Button variant="outline" type="button" onClick={() => onOpenChange(false)} disabled={createOffer.isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={createOffer.isPending || isUploading || (policyBlocked && !override)}>
              {createOffer.isPending ? <Spinner className="text-primary-foreground" /> : null}
              Create offer
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
