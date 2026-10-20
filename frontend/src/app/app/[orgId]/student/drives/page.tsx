import type { Metadata } from "next";
import { Suspense } from "react";

import { PortalGate } from "@/components/portal/portal-gate";
import { StudentDrives } from "@/components/student/student-drives";

export const metadata: Metadata = { title: "Drives" };

export default function StudentDrivesRoute() {
  return (
    <PortalGate persona="student">
      <Suspense>
        <StudentDrives />
      </Suspense>
    </PortalGate>
  );
}
