"use client";

import * as React from "react";
import { toast } from "sonner";
import { Award, CalendarCheck, CalendarClock, Download, PartyPopper } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { ErrorState } from "@/components/common/error-state";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { LookupValue } from "@/components/records/field-value";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { ApiError, downloadFile, errorMessage, useRecords, useRespondToOffer } from "@/lib/api";
import { formatLpa } from "@/lib/campushire";
import { formatDate, humanize, isCurrencyValue, isFileReference, parseLocalDate } from "@/lib/metadata/format";
import { TONE_CLASSES, type Tone } from "@/lib/metadata/tones";
import { cn } from "@/lib/utils";
import type { RecordItem } from "@/types/records";

const STATUS_TONES: Record<string, Tone> = { extended: "indigo", accepted: "green", declined: "neutral", revoked: "red" };

function str(record: RecordItem, key: string) {
  const value = record.data[key];
  return typeof value === "string" ? value : null;
}

function daysLeft(date: string | null) {
  const parsed = date ? parseLocalDate(date) : null;
  if (!parsed) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.round((parsed.getTime() - today.getTime()) / 86_400_000);
}

type Decision = { record: RecordItem; decision: "accept" | "decline" };

function OfferCard({ record, onDecide }: { record: RecordItem; onDecide: (decision: Decision) => void }) {
  const { orgId } = useOrg();
  const [downloading, setDownloading] = React.useState(false);
  const status = str(record, "offer_status") ?? "extended";
  const company = str(record, "company");
  const respondBy = str(record, "respond_by");
  const joining = str(record, "joining_date");
  const ctc = record.data.ctc;
  const letter = record.data.offer_letter;
  const left = status === "extended" ? daysLeft(respondBy) : null;
  const expired = left !== null && left < 0;

  const download = async () => {
    if (!isFileReference(letter)) return;
    setDownloading(true);
    try {
      await downloadFile(orgId, letter.fileId, letter.name ?? "offer-letter.pdf");
    } catch (error) {
      toast.error("Couldn't download the offer letter", { description: errorMessage(error) });
    } finally {
      setDownloading(false);
    }
  };

  return (
    <li className={cn("flex flex-col rounded-lg border bg-card", status === "accepted" ? "border-success/30" : "border-border")}>
      <div className="flex flex-col gap-3 p-4">
        <div className="flex items-start gap-3">
          <span className="grid size-10 shrink-0 place-items-center rounded-lg bg-primary-soft text-primary-soft-foreground">
            {status === "accepted" ? <PartyPopper className="size-5" /> : <Award className="size-5" />}
          </span>
          <div className="min-w-0 flex-1">
            <div className="truncate text-[15px] font-semibold">
              {company ? <LookupValue orgId={orgId} targetModuleKey="company" id={company} link={false} /> : "Company"}
            </div>
            <p className="text-[13px] text-muted-foreground">{str(record, "offer_type") ? humanize(str(record, "offer_type") ?? "") : "Offer"}</p>
          </div>
          <Badge variant="neutral" className={TONE_CLASSES[STATUS_TONES[status] ?? "neutral"]}>
            {humanize(status)}
          </Badge>
        </div>
        <p className="text-2xl font-semibold tracking-[-0.03em] tabular">{isCurrencyValue(ctc) ? formatLpa(Number(ctc.amount)) : "–"}</p>
        <dl className="grid gap-1.5 text-[13px] sm:grid-cols-2">
          {joining ? (
            <div className="flex items-center gap-1.5 text-muted-foreground">
              <CalendarCheck className="size-3.5" />
              <dt className="sr-only">Joining date</dt>
              <dd>Joining {formatDate(joining)}</dd>
            </div>
          ) : null}
          {respondBy && status === "extended" ? (
            <div className={cn("flex items-center gap-1.5", expired ? "text-destructive" : left !== null && left <= 2 ? "text-warning-soft-foreground" : "text-muted-foreground")}>
              <CalendarClock className="size-3.5" />
              <dt className="sr-only">Respond by</dt>
              <dd>
                Respond by {formatDate(respondBy)}
                {left !== null ? (expired ? " (expired)" : left === 0 ? " (today)" : ` (${left} ${left === 1 ? "day" : "days"} left)`) : ""}
              </dd>
            </div>
          ) : null}
        </dl>
      </div>
      <div className="mt-auto flex flex-wrap items-center gap-2 border-t border-border px-4 py-3">
        {isFileReference(letter) ? (
          <Button variant="outline" size="sm" disabled={downloading} onClick={() => void download()}>
            {downloading ? <Spinner /> : <Download />} Offer letter
          </Button>
        ) : null}
        {status === "extended" && !expired ? (
          <div className="ml-auto flex gap-2">
            <Button variant="outline" size="sm" onClick={() => onDecide({ record, decision: "decline" })}>
              Decline
            </Button>
            <Button size="sm" onClick={() => onDecide({ record, decision: "accept" })}>
              Accept offer
            </Button>
          </div>
        ) : null}
      </div>
    </li>
  );
}

/** The student's offers with accept / decline before the response deadline. */
export function StudentOffers() {
  const { orgId } = useOrg();
  const offers = useRecords(orgId, "offer", { size: 50, sort: "-created_at" });
  const respond = useRespondToOffer(orgId);
  const [pending, setPending] = React.useState<Decision | null>(null);
  const items = offers.data?.items ?? [];

  const confirm = async () => {
    if (!pending) return;
    try {
      await respond.mutateAsync({ offerId: pending.record.id, decision: pending.decision });
      toast.success(pending.decision === "accept" ? "Offer accepted. Congratulations!" : "Offer declined");
      setPending(null);
    } catch (error) {
      toast.error(pending.decision === "accept" ? "Couldn't accept the offer" : "Couldn't decline the offer", {
        description: error instanceof ApiError ? error.friendlyMessage : errorMessage(error),
      });
      setPending(null);
    }
  };

  return (
    <>
      <PageHeader title="Offers" description="Offers you've received. Respond before the deadline." />
      <PageBody className="max-w-4xl">
        {offers.isLoading ? (
          <div className="grid gap-4 md:grid-cols-2">
            <Skeleton className="h-48 rounded-lg" />
            <Skeleton className="h-48 rounded-lg" />
          </div>
        ) : offers.isError ? (
          <ErrorState error={offers.error} onRetry={() => void offers.refetch()} />
        ) : items.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border-strong">
            <EmptyState icon={Award} title="No offers yet" description="When a company makes you an offer, it shows up here and you're notified." />
          </div>
        ) : (
          <ul className="grid gap-4 md:grid-cols-2" aria-label="Offers">
            {items.map((record) => (
              <OfferCard key={record.id} record={record} onDecide={setPending} />
            ))}
          </ul>
        )}
      </PageBody>
      <AlertDialog open={Boolean(pending)} onOpenChange={(open) => !open && !respond.isPending && setPending(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{pending?.decision === "accept" ? "Accept this offer?" : "Decline this offer?"}</AlertDialogTitle>
            <AlertDialogDescription>
              {pending?.decision === "accept"
                ? "Your placement cell and the company see that you accepted. Accepting doesn't decline your other offers."
                : "The company is told you declined. This can't be undone."}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={respond.isPending}>Not now</AlertDialogCancel>
            <Button variant={pending?.decision === "decline" ? "destructive" : "default"} disabled={respond.isPending} onClick={() => void confirm()}>
              {respond.isPending ? <Spinner className="text-current" /> : null}
              {pending?.decision === "accept" ? "Accept offer" : "Decline offer"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
