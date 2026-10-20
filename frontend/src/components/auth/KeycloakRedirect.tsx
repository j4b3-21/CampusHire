"use client";

import * as React from "react";
import { useRouter } from "next/navigation";

import { LoadingScreen } from "@/components/common/loading-screen";
import { useAuth } from "@/hooks/use-auth";
import { resetPasswordUrl } from "@/lib/auth/keycloak";

/** Hands off to a Keycloak page (registration or password reset); the app never collects credentials. */
export function KeycloakRedirect({ action }: { action: "register" | "reset-password" }) {
  const router = useRouter();
  const { status, register } = useAuth();
  const started = React.useRef(false);

  React.useEffect(() => {
    if (started.current) return;
    if (action === "reset-password") {
      started.current = true;
      window.location.replace(resetPasswordUrl());
      return;
    }
    if (status === "authenticated") {
      started.current = true;
      router.replace("/app");
    } else if (status === "unauthenticated") {
      started.current = true;
      register().catch(() => router.replace("/login"));
    }
  }, [action, status, register, router]);

  return <LoadingScreen label={action === "register" ? "Opening registration…" : "Opening password reset…"} />;
}
