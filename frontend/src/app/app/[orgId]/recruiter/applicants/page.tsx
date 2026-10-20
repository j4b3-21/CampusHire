import type { Metadata } from "next";
import { Suspense } from "react";

import { PortalGate } from "@/components/portal/portal-gate";
import { RecruiterApplicants } from "@/components/recruiter/recruiter-applicants";

export const metadata: Metadata = { title: "Applicants" };

export default function RecruiterApplicantsRoute() {
  return (
    <PortalGate persona="recruiter" allowStaff>
      <Suspense>
        <RecruiterApplicants />
      </Suspense>
    </PortalGate>
  );
}
