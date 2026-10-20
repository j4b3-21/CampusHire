import type { Metadata } from "next";

import { PortalGate } from "@/components/portal/portal-gate";
import { StudentApplications } from "@/components/student/student-applications";

export const metadata: Metadata = { title: "My applications" };

export default function StudentApplicationsRoute() {
  return (
    <PortalGate persona="student">
      <StudentApplications />
    </PortalGate>
  );
}
