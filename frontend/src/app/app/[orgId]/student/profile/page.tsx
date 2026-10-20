import type { Metadata } from "next";

import { PortalGate } from "@/components/portal/portal-gate";
import { StudentProfile } from "@/components/student/student-profile";

export const metadata: Metadata = { title: "Your profile" };

export default function StudentProfileRoute() {
  return (
    <PortalGate persona="student">
      <StudentProfile />
    </PortalGate>
  );
}
