"use client";

import Link from "next/link";
import { Building2, BriefcaseBusiness, LayoutDashboard, Settings, Users2 } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";
import { useAuth } from "@/hooks/use-auth";

const baseItems = [
  { href: "/dashboard", label: "Dashboard", icon: LayoutDashboard },
  { href: "#placements", label: "Placements", icon: BriefcaseBusiness },
  { href: "#students", label: "Students", icon: Users2 },
  { href: "#institutes", label: "Institutes", icon: Building2 },
  { href: "#settings", label: "Settings", icon: Settings },
];

export function AppSidebar() {
  const { roles } = useAuth();

  const items = baseItems.filter((item) => {
    if (item.href === "#institutes") return roles.some((role) => role.includes("ADMIN"));
    return true;
  });

  return (
    <aside className="hidden h-screen w-72 flex-col border-r border-border/70 bg-card/50 px-4 py-5 backdrop-blur xl:flex">
      <div className="flex items-center gap-3 px-2">
        <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-primary text-primary-foreground shadow-lg shadow-primary/20">
          CH
        </div>
        <div>
          <p className="text-sm font-semibold">CampusHire</p>
          <p className="text-xs text-muted-foreground">Placement platform</p>
        </div>
      </div>

      <div className="mt-6 rounded-2xl border border-border/70 bg-background/60 p-4">
        <p className="text-xs uppercase tracking-[0.18em] text-muted-foreground">Role aware shell</p>
        <div className="mt-3 flex flex-wrap gap-2">
          {roles.length ? roles.map((role) => <Badge key={role}>{role}</Badge>) : <Badge>Guest</Badge>}
        </div>
      </div>

      <Separator className="my-5" />

      <nav className="flex flex-1 flex-col gap-1">
        {items.map((item) => {
          const Icon = item.icon;
          return (
            <Link
              key={item.href}
              href={item.href.startsWith("#") ? `/dashboard${item.href}` : item.href}
              className={cn(
                "flex items-center gap-3 rounded-xl px-4 py-3 text-sm font-medium text-muted-foreground transition hover:bg-accent hover:text-foreground"
              )}
            >
              <Icon className="h-4 w-4" />
              {item.label}
            </Link>
          );
        })}
      </nav>

      <div className="rounded-2xl border border-border/70 bg-background/60 p-4">
        <p className="text-sm font-medium">Need permissions?</p>
        <p className="mt-1 text-xs text-muted-foreground">Role assignments are controlled through Keycloak and reflected in the backend.</p>
        <Link
          href="/login"
          className="mt-4 inline-flex h-10 w-full items-center justify-center rounded-md border border-border bg-transparent px-4 py-2 text-sm font-medium text-foreground transition hover:bg-accent"
        >
          Switch account
        </Link>
      </div>
    </aside>
  );
}
