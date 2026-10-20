"use client";

import * as React from "react";
import Link from "next/link";
import { Lock } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { orgPath } from "@/lib/navigation";
import type { Persona } from "@/types/campushire";

const AUDIENCE: Partial<Record<Persona, string>> = { student: "students", recruiter: "recruiters" };

/** Renders portal pages only for the matching persona (admins may open recruiter pages too). */
export function PortalGate({ persona, allowStaff = false, children }: { persona: Persona; allowStaff?: boolean; children: React.ReactNode }) {
  const { orgId, persona: current, isStaff, loading } = useOrg();
  if (loading) {
    return (
      <div className="flex flex-col gap-4 px-4 pt-6 sm:px-6 lg:px-8">
        <Skeleton className="h-6 w-48" />
        <Skeleton className="h-4 w-72" />
        <Skeleton className="mt-4 h-64 w-full rounded-lg" />
      </div>
    );
  }
  if (current !== persona && !(allowStaff && isStaff)) {
    return (
      <EmptyState
        icon={Lock}
        title={`This page is for ${AUDIENCE[persona] ?? persona}`}
        description="Your role in this institute uses a different set of pages."
        action={
          <Button variant="outline" asChild>
            <Link href={orgPath(orgId)}>Back to home</Link>
          </Button>
        }
      />
    );
  }
  return <>{children}</>;
}
