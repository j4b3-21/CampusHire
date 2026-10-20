import { Suspense } from "react";

import { ModuleDetailPage } from "@/components/records/module-pages";

export default async function RecordRoute({
  params,
}: {
  params: Promise<{ orgId: string; moduleKey: string; recordId: string }>;
}) {
  const { moduleKey, recordId } = await params;
  return (
    <Suspense>
      <ModuleDetailPage moduleKey={moduleKey} recordId={recordId} />
    </Suspense>
  );
}
