import type { Metadata } from "next";
import { Suspense } from "react";

import { ImportWizard } from "@/components/import/import-wizard";

export const metadata: Metadata = { title: "Data import" };

export default function ImportRoute() {
  return (
    <Suspense>
      <ImportWizard />
    </Suspense>
  );
}
