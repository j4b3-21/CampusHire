import type { Metadata } from "next";
import { Suspense } from "react";

import { Onboarding } from "@/components/app/onboarding";
import { LoadingScreen } from "@/components/common/loading-screen";

export const metadata: Metadata = { title: "Get started" };

export default function OnboardingPage() {
  return (
    <Suspense fallback={<LoadingScreen />}>
      <Onboarding />
    </Suspense>
  );
}
