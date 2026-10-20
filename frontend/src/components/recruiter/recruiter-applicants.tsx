"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { ArrowUpRight, CalendarRange } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { DriveStatusBadge } from "@/components/dashboard/dashboard-kit";
import { ApplicantsPanel } from "@/components/drives/applicants-panel";
import { ErrorState } from "@/components/common/error-state";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { useRecords } from "@/lib/api";
import { recordTitle } from "@/lib/metadata/fields";
import { recordPath } from "@/lib/navigation";

/** Applicants per drive for recruiters (their company's drives; the API scopes everything). */
export function RecruiterApplicants() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { orgId, moduleByKey } = useOrg();
  const drives = useRecords(orgId, "drive", { size: 100, sort: "-updated_at" });
  const definition = moduleByKey("drive");
  const items = (drives.data?.items ?? []).filter((drive) => drive.data.drive_status !== "draft");
  const requested = searchParams.get("drive");
  const driveId = items.some((drive) => drive.id === requested) ? requested : items[0]?.id ?? null;
  const drive = items.find((item) => item.id === driveId);
  const company = typeof drive?.data.company === "string" ? drive.data.company : null;

  return (
    <>
      <PageHeader
        title="Applicants"
        description="Students who applied to your drives. Shortlist in bulk or by uploading roll numbers."
        actions={
          drive ? (
            <Button variant="outline" asChild>
              <Link href={recordPath(orgId, "drive", drive.id)}>
                Open drive <ArrowUpRight />
              </Link>
            </Button>
          ) : null
        }
      >
        {items.length > 0 ? (
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex w-full flex-col gap-1 sm:w-96">
              <Label htmlFor="drive-picker" className="text-xs text-muted-foreground">
                Drive
              </Label>
              <Select value={driveId ?? undefined} onValueChange={(value) => router.replace(`${pathname}?drive=${value}`, { scroll: false })}>
                <SelectTrigger id="drive-picker">
                  <SelectValue placeholder="Choose a drive" />
                </SelectTrigger>
                <SelectContent>
                  {items.map((item) => (
                    <SelectItem key={item.id} value={item.id}>
                      {recordTitle(definition, item)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            {drive ? <DriveStatusBadge status={typeof drive.data.drive_status === "string" ? drive.data.drive_status : null} className="mb-2" /> : null}
          </div>
        ) : null}
      </PageHeader>
      <PageBody>
        {drives.isLoading ? (
          <Skeleton className="h-72 w-full rounded-lg" />
        ) : drives.isError ? (
          <ErrorState error={drives.error} onRetry={() => void drives.refetch()} />
        ) : !driveId ? (
          <div className="rounded-lg border border-dashed border-border-strong">
            <EmptyState icon={CalendarRange} title="No published drives yet" description="Applicants appear here once your placement cell publishes a drive for your company." />
          </div>
        ) : (
          <ApplicantsPanel key={driveId} driveId={driveId} companyId={company} />
        )}
      </PageBody>
    </>
  );
}
