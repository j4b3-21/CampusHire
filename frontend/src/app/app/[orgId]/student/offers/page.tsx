import type { Metadata } from "next";

import { PortalGate } from "@/components/portal/portal-gate";
import { StudentOffers } from "@/components/student/student-offers";

export const metadata: Metadata = { title: "Offers" };

export default function StudentOffersRoute() {
  return (
    <PortalGate persona="student">
      <StudentOffers />
    </PortalGate>
  );
}
