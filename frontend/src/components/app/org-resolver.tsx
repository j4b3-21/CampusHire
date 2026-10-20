"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { LogOut } from "lucide-react";

import { ErrorState } from "@/components/common/error-state";
import { LoadingScreen } from "@/components/common/loading-screen";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/hooks/use-auth";
import { ApiError, useOrganizations } from "@/lib/api";
import { BrandMark } from "@/components/common/brand";
import { useUiStore } from "@/store/ui-store";

/** `/app`: picks the last used institute, or sends first-time users to onboarding. */
export function OrgResolver() {
  const router = useRouter();
  const orgs = useOrganizations();
  const lastOrgId = useUiStore((state) => state.lastOrgId);
  const { logout } = useAuth();

  React.useEffect(() => {
    if (!orgs.data) return;
    if (orgs.data.length === 0) {
      router.replace("/app/onboarding");
      return;
    }
    const target = orgs.data.find((org) => org.id === lastOrgId) ?? orgs.data[0];
    router.replace(`/app/${target.id}`);
  }, [orgs.data, lastOrgId, router]);

  if (orgs.isError) {
    const status = orgs.error instanceof ApiError ? orgs.error.status : 0;
    return (
      <div className="flex min-h-dvh flex-col items-center justify-center gap-2 px-6">
        <BrandMark className="size-8" />
        <ErrorState
          error={orgs.error}
          title="CampusHire is not available right now"
          description={
            <>
              You&apos;re signed in, but the CampusHire server didn&apos;t answer. Try again in a moment.
              <span className="mt-1 block text-xs text-subtle-foreground">
                {status ? `The server responded with status ${status}.` : "The server could not be reached."}
              </span>
            </>
          }
          alwaysRetry
          onRetry={() => void orgs.refetch()}
          action={
            <Button variant="ghost" size="sm" onClick={() => void logout()}>
              <LogOut /> Sign out
            </Button>
          }
        />
      </div>
    );
  }

  return <LoadingScreen label="Opening your institute…" />;
}
