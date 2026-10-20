import { Suspense } from "react";

import { ModuleListPage } from "@/components/records/module-pages";

export default async function ModuleListRoute({ params }: { params: Promise<{ orgId: string; moduleKey: string }> }) {
  const { moduleKey } = await params;
  return (
    <Suspense>
      <ModuleListPage moduleKey={moduleKey} />
    </Suspense>
  );
}
