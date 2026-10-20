import { Suspense } from "react";

import { ModuleBoardPage } from "@/components/records/module-pages";

export default async function ModuleBoardRoute({ params }: { params: Promise<{ orgId: string; moduleKey: string }> }) {
  const { moduleKey } = await params;
  return (
    <Suspense>
      <ModuleBoardPage moduleKey={moduleKey} />
    </Suspense>
  );
}
