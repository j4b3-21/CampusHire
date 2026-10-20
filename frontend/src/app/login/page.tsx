import type { Metadata } from "next";
import { Suspense } from "react";

import { LoginScreen } from "@/components/auth/LoginScreen";
import { LoadingScreen } from "@/components/common/loading-screen";

export const metadata: Metadata = { title: "Sign in" };

export default function LoginPage() {
  return (
    <Suspense fallback={<LoadingScreen />}>
      <LoginScreen />
    </Suspense>
  );
}
