"use client";

import * as React from "react";
import Link from "next/link";
import { Lock } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { orgPath } from "@/lib/navigation";

/** Renders admin pages for organization owners/admins only. */
export function AdminGate({ children }: { children: React.ReactNode }) {
  const { orgId, isAdmin, loading } = useOrg();
  if (loading) {
    return (
      <div className="flex flex-col gap-4 px-4 pt-6 sm:px-6 lg:px-8">
        <Skeleton className="h-6 w-48" />
        <Skeleton className="h-4 w-80" />
        <Skeleton className="mt-4 h-72 w-full rounded-lg" />
      </div>
    );
  }
  if (!isAdmin) {
    return (
      <EmptyState
        icon={Lock}
        title="Admins only"
        description="Ask your placement officer if you need access to this page."
        action={
          <Button variant="outline" asChild>
            <Link href={orgPath(orgId)}>Back to dashboard</Link>
          </Button>
        }
      />
    );
  }
  return <>{children}</>;
}

/** Inline field error / hint text. */
export function FieldMessage({ id, error, hint }: { id?: string; error?: string | null; hint?: React.ReactNode }) {
  if (error) {
    return (
      <p id={id} className="text-xs text-destructive">
        {error}
      </p>
    );
  }
  if (hint) {
    return (
      <p id={id} className="text-xs text-muted-foreground">
        {hint}
      </p>
    );
  }
  return null;
}

/** Lowercase API key from a label (`^[a-z][a-z0-9_]{0,62}$`). */
export function toApiKey(label: string) {
  const key = label
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .replace(/^[0-9_]+/, "");
  return key.slice(0, 63);
}

export const API_KEY_PATTERN = /^[a-z][a-z0-9_]{0,62}$/;
