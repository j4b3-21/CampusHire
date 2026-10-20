"use client";

import { useRouter } from "next/navigation";
import { Lock, UserRoundX } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { ErrorState } from "@/components/common/error-state";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { RecordForm } from "@/components/records/record-form";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { useCampusHireMe, useModule, useRecord } from "@/lib/api";
import { orgPath } from "@/lib/navigation";

/** The student's own record: contact details, links, skills and resume are editable; academics are locked. */
export function StudentProfile() {
  const router = useRouter();
  const { orgId, capabilities } = useOrg();
  const me = useCampusHireMe(orgId);
  const studentId = me.data?.student?.id;
  const definition = useModule(orgId, "student");
  const record = useRecord(orgId, "student", studentId, { silent: true });
  const caps = capabilities("student");
  const loading = me.isLoading || definition.isLoading || (Boolean(studentId) && record.isLoading);

  return (
    <>
      <PageHeader
        title="Your profile"
        description={
          <span className="inline-flex items-center gap-1.5">
            <Lock className="size-3.5" /> Academic details are maintained by your placement cell. Keep your contact details, links and resume current.
          </span>
        }
      />
      <PageBody className="max-w-5xl pb-0">
        {loading ? (
          <div className="flex flex-col gap-4">
            <Skeleton className="h-9 w-72" />
            <Skeleton className="h-64 w-full rounded-lg" />
          </div>
        ) : me.isError ? (
          <ErrorState error={me.error} onRetry={() => void me.refetch()} />
        ) : !studentId ? (
          <EmptyState icon={UserRoundX} title="No student record linked" description="Your account isn't linked to a student record yet. Contact your placement cell." />
        ) : record.isError || !record.data || !definition.data ? (
          <ErrorState error={record.error ?? definition.error} onRetry={() => void record.refetch()} />
        ) : (
          <RecordForm
            key={`${record.data.id}-${record.data.version}`}
            orgId={orgId}
            module={definition.data}
            capabilities={caps}
            record={record.data}
            onSaved={() => void me.refetch()}
            onCancel={() => router.push(orgPath(orgId))}
            onReload={async () => (await record.refetch()).data}
          />
        )}
      </PageBody>
    </>
  );
}
