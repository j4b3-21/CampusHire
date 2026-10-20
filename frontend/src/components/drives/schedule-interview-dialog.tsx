"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { toast } from "sonner";

import { useOrg } from "@/components/app/org-context";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";
import { ApiError, useCreateRecord } from "@/lib/api";
import { selectOptions } from "@/lib/metadata/fields";
import { recordPath } from "@/lib/navigation";
import type { Applicant } from "@/types/campushire";
import type { RecordData } from "@/types/records";

/**
 * Creates an `interview` record for one applicant; application, student, company and drive are filled in so the
 * scheduler only picks the round, time and place.
 */
export function ScheduleInterviewDialog({
  applicant,
  driveId,
  companyId,
  onClose,
}: {
  applicant: Applicant | null;
  driveId: string;
  companyId: string | null;
  onClose: () => void;
}) {
  const router = useRouter();
  const { orgId, moduleByKey } = useOrg();
  const create = useCreateRecord(orgId, "interview");
  const modeField = moduleByKey("interview")?.fields.find((field) => field.apiKey === "mode");
  const modes = modeField ? selectOptions(modeField) : [];
  const [round, setRound] = React.useState("");
  const [when, setWhen] = React.useState("");
  const [mode, setMode] = React.useState<string>("");
  const [location, setLocation] = React.useState("");
  const [errors, setErrors] = React.useState<Record<string, string>>({});

  const close = () => {
    setRound("");
    setWhen("");
    setMode("");
    setLocation("");
    setErrors({});
    onClose();
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!applicant) return;
    const next: Record<string, string> = {};
    if (!round.trim()) next.round_name = "Enter the round, e.g. Technical interview 1";
    const date = when ? new Date(when) : null;
    if (!date || Number.isNaN(date.getTime())) next.scheduled_at = "Pick a date and time";
    setErrors(next);
    if (Object.keys(next).length > 0 || !date) return;

    const data: RecordData = {
      application: applicant.applicationId,
      student: applicant.student.id,
      round_name: round.trim(),
      scheduled_at: date.toISOString().replace(/\.000Z$/, "Z"),
      drive: driveId,
    };
    if (companyId) data.company = companyId;
    if (mode) data.mode = mode;
    if (location.trim()) data.location = location.trim();
    try {
      const record = await create.mutateAsync(data);
      toast.success("Interview scheduled", {
        description: `${applicant.student.fullName} is notified in the app.`,
        action: { label: "Open", onClick: () => router.push(recordPath(orgId, "interview", record.id)) },
      });
      close();
    } catch (error) {
      if (error instanceof ApiError && error.isValidation) {
        const fieldErrors = error.fieldErrors();
        setErrors(fieldErrors);
        if (!fieldErrors.round_name && !fieldErrors.scheduled_at && !fieldErrors.location) toast.error(error.friendlyMessage, { description: Object.values(fieldErrors).join(" ") });
      }
    }
  };

  return (
    <Dialog open={Boolean(applicant)} onOpenChange={(open) => !open && close()}>
      <DialogContent>
        <form onSubmit={submit} className="grid gap-4" noValidate>
          <DialogHeader>
            <DialogTitle>Schedule interview</DialogTitle>
            <DialogDescription>
              {applicant ? (
                <>
                  {applicant.student.fullName} · <span className="font-mono text-xs">{applicant.student.rollNumber}</span>
                </>
              ) : null}
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-1.5">
            <Label htmlFor="interview-round">
              Round <span className="text-destructive">*</span>
            </Label>
            <Input id="interview-round" value={round} onChange={(event) => setRound(event.target.value)} placeholder="Technical interview 1" aria-invalid={Boolean(errors.round_name) || undefined} autoFocus />
            {errors.round_name ? <p className="text-xs text-destructive">{errors.round_name}</p> : null}
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="grid gap-1.5">
              <Label htmlFor="interview-when">
                Date and time <span className="text-destructive">*</span>
              </Label>
              <Input id="interview-when" type="datetime-local" value={when} onChange={(event) => setWhen(event.target.value)} aria-invalid={Boolean(errors.scheduled_at) || undefined} />
              {errors.scheduled_at ? <p className="text-xs text-destructive">{errors.scheduled_at}</p> : null}
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="interview-mode">Mode</Label>
              <Select value={mode} onValueChange={setMode}>
                <SelectTrigger id="interview-mode">
                  <SelectValue placeholder="Choose…" />
                </SelectTrigger>
                <SelectContent>
                  {modes.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="interview-location">Location or link</Label>
            <Input id="interview-location" value={location} onChange={(event) => setLocation(event.target.value)} placeholder="Seminar hall 2 or a meeting link" />
            {errors.location ? <p className="text-xs text-destructive">{errors.location}</p> : null}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={close} disabled={create.isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? <Spinner className="text-primary-foreground" /> : null}
              Schedule
            </Button>
          </DialogFooter>
          <p className="text-xs text-muted-foreground">
            Need more fields? Use the full{" "}
            <Link href={`/app/${orgId}/m/interview/new`} className="font-medium text-primary hover:underline">
              interview form
            </Link>
            .
          </p>
        </form>
      </DialogContent>
    </Dialog>
  );
}
