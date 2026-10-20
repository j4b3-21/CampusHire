"use client";

import * as React from "react";
import Link from "next/link";
import { Building2 } from "lucide-react";

import { OrgProvider, useOrg } from "@/components/app/org-context";
import { AppHeader } from "@/components/layout/app-header";
import { AppSidebar } from "@/components/layout/app-sidebar";
import { CommandPalette } from "@/components/layout/command-palette";
import { NotificationStream } from "@/components/notifications/notification-stream";
import { ErrorState } from "@/components/common/error-state";
import { LoadingScreen } from "@/components/common/loading-screen";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { useOrganizations } from "@/lib/api";
import { useUiStore } from "@/store/ui-store";

function ShellFrame({ children }: { children: React.ReactNode }) {
  const { orgId } = useOrg();
  const setLastOrgId = useUiStore((state) => state.setLastOrgId);

  React.useEffect(() => {
    setLastOrgId(orgId);
  }, [orgId, setLastOrgId]);

  return (
    <div className="flex min-h-dvh bg-background">
      <AppSidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <AppHeader />
        <main id="main" className="flex min-w-0 flex-1 flex-col">
          {children}
        </main>
      </div>
      <CommandPalette />
      <NotificationStream />
    </div>
  );
}

export function AppShell({ orgId, children }: { orgId: string; children: React.ReactNode }) {
  const orgs = useOrganizations();

  if (orgs.isLoading) return <LoadingScreen label="Loading your institute…" />;

  if (orgs.isError) {
    return (
      <div className="grid min-h-dvh place-items-center px-6">
        <ErrorState error={orgs.error} onRetry={() => void orgs.refetch()} />
      </div>
    );
  }

  if (!orgs.data?.some((org) => org.id === orgId)) {
    return (
      <div className="grid min-h-dvh place-items-center px-6">
        <EmptyState
          icon={Building2}
          title="Institute not found"
          description="It may have been archived, or you're not a member of it. Pick one of your institutes instead."
          action={
            <Button asChild>
              <Link href="/app">Go to my institutes</Link>
            </Button>
          }
        />
      </div>
    );
  }

  return (
    <OrgProvider orgId={orgId}>
      <ShellFrame>{children}</ShellFrame>
    </OrgProvider>
  );
}
