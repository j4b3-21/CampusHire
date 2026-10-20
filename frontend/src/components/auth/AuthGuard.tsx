"use client";

import * as React from "react";
import { usePathname, useRouter } from "next/navigation";

import { LoadingScreen } from "@/components/common/loading-screen";
import { useAuth } from "@/hooks/use-auth";

/** Renders children only for an authenticated Keycloak session; otherwise sends the user to `/login?next=…`. */
export function AuthGuard({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const { status } = useAuth();

  React.useEffect(() => {
    if (status === "unauthenticated") {
      const next = `${pathname}${window.location.search}`;
      router.replace(`/login?next=${encodeURIComponent(next)}`);
    }
  }, [status, pathname, router]);

  if (status !== "authenticated") {
    return <LoadingScreen label={status === "loading" ? "Checking your session…" : "Redirecting to sign in…"} />;
  }
  return <>{children}</>;
}
