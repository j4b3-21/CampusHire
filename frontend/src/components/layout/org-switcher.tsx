"use client";

import Link from "next/link";
import { Check, ChevronsUpDown, Plus } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

function OrgAvatar({ name, className }: { name: string; className?: string }) {
  return (
    <span
      aria-hidden
      className={cn(
        "grid size-6 shrink-0 place-items-center rounded-md bg-foreground text-[11px] font-semibold text-background",
        className
      )}
    >
      {name.trim().charAt(0).toUpperCase() || "?"}
    </span>
  );
}

export function OrgSwitcher({ collapsed }: { collapsed?: boolean }) {
  const { organization, organizations, roleLabel, orgId } = useOrg();

  if (!organization) {
    return (
      <div className="flex h-10 items-center gap-2 px-2">
        <Skeleton className="size-6" />
        {!collapsed ? <Skeleton className="h-4 w-28" /> : null}
      </div>
    );
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className={cn(
          "focus-ring flex h-10 w-full items-center gap-2 rounded-md px-2 text-left transition-colors hover:bg-sidebar-active data-[state=open]:bg-sidebar-active",
          collapsed && "justify-center px-0"
        )}
        aria-label={`Current institute: ${organization.name}. Switch institute`}
      >
        <OrgAvatar name={organization.name} />
        {!collapsed ? (
          <>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[13px] font-semibold leading-tight text-foreground">
                {organization.name}
              </span>
              <span className="block truncate text-[11px] leading-tight text-muted-foreground">{roleLabel}</span>
            </span>
            <ChevronsUpDown className="size-3.5 shrink-0 text-muted-foreground" />
          </>
        ) : null}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-64">
        <DropdownMenuLabel>Institutes</DropdownMenuLabel>
        {organizations.map((org) => (
          <DropdownMenuItem key={org.id} asChild>
            <Link href={`/app/${org.id}`}>
              <OrgAvatar name={org.name} className="size-5 text-[10px]" />
              <span className="flex-1 truncate">{org.name}</span>
              {org.id === orgId ? <Check className="!text-foreground" /> : null}
            </Link>
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild>
          <Link href="/app/onboarding?create=1">
            <Plus /> Create institute
          </Link>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
