"use client";

import * as React from "react";
import { useRouter } from "next/navigation";

import { LoadingScreen } from "@/components/common/loading-screen";
import { useAuth } from "@/hooks/use-auth";

export function ProtectedGuard({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const { authenticated, loading } = useAuth();

  React.useEffect(() => {
    if (!loading && !authenticated) {
      router.replace("/login");
    }
  }, [authenticated, loading, router]);

  if (loading) {
    return <LoadingScreen />;
  }

  if (!authenticated) {
    return <LoadingScreen label="Redirecting to sign in..." />;
  }

  return <>{children}</>;
}

export function PublicGuard({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const { authenticated, loading } = useAuth();

  React.useEffect(() => {
    if (!loading && authenticated) {
      router.replace("/dashboard");
    }
  }, [authenticated, loading, router]);

  if (loading) {
    return <LoadingScreen />;
  }

  return <>{children}</>;
}
