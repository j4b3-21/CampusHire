"use client";

import * as React from "react";
import Link from "next/link";
import { useParams, usePathname } from "next/navigation";
import { Menu, Search } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { NotificationsPopover } from "@/components/layout/notifications-popover";
import { UserMenu } from "@/components/layout/user-menu";
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";
import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/kbd";
import { Skeleton } from "@/components/ui/skeleton";
import { useRecord } from "@/lib/api";
import { recordTitle } from "@/lib/metadata/fields";
import { ADMIN_NAV, adminPath, modulePath, orgPath, recordPath } from "@/lib/navigation";
import { useUiStore } from "@/store/ui-store";

interface Crumb {
  label: React.ReactNode;
  href?: string;
}

const PORTAL_LABELS: [string, string][] = [
  ["/analytics", "Analytics"],
  ["/notifications", "Notifications"],
  ["/student/drives", "Drives"],
  ["/student/applications", "My applications"],
  ["/student/offers", "Offers"],
  ["/student/profile", "Profile"],
  ["/recruiter/applicants", "Applicants"],
];

function useCrumbs(): Crumb[] {
  const pathname = usePathname();
  const params = useParams<{ orgId: string; moduleKey?: string; recordId?: string; roleKey?: string }>();
  const { orgId, organization, moduleByKey, persona } = useOrg();
  const adminMatch = pathname.match(/\/admin\/([a-z-]+)(?:\/([^/]+))?/);
  const portalLabel = PORTAL_LABELS.find(([suffix]) => pathname.endsWith(suffix))?.[1];
  const definition = params.moduleKey ? moduleByKey(params.moduleKey) : undefined;
  const record = useRecord(orgId, adminMatch ? undefined : params.moduleKey, params.recordId, { silent: true });

  const crumbs: Crumb[] = [{ label: organization?.name ?? <Skeleton className="h-3.5 w-20" />, href: orgPath(orgId) }];
  if (adminMatch) {
    const section = adminMatch[1];
    const item = ADMIN_NAV.find((entry) => entry.section === section);
    crumbs.push({ label: "Admin" });
    crumbs.push({ label: item?.label ?? section, href: adminMatch[2] ? adminPath(orgId, section) : undefined });
    if (adminMatch[2]) {
      const sub = decodeURIComponent(adminMatch[2]);
      crumbs.push({ label: definition?.labelPlural ?? (sub === "new" ? "New" : sub) });
    }
  } else if (params.moduleKey) {
    crumbs.push({
      label: definition?.labelPlural ?? <Skeleton className="h-3.5 w-16" />,
      href: modulePath(orgId, params.moduleKey),
    });
    if (pathname.endsWith("/new")) crumbs.push({ label: "New" });
    if (pathname.endsWith("/board")) crumbs.push({ label: "Board" });
    if (params.recordId) {
      crumbs.push({
        label: record.data ? recordTitle(definition, record.data) : <Skeleton className="h-3.5 w-24" />,
        href: recordPath(orgId, params.moduleKey, params.recordId),
      });
      if (pathname.endsWith("/edit")) crumbs.push({ label: "Edit" });
    }
  } else if (portalLabel) {
    crumbs.push({ label: portalLabel });
  } else if (pathname.endsWith("/profile")) {
    crumbs.push({ label: "Profile" });
  } else {
    crumbs.push({ label: persona === "student" || persona === "recruiter" ? "Home" : "Dashboard" });
  }
  return crumbs;
}

export function AppHeader() {
  const crumbs = useCrumbs();
  const { orgId } = useOrg();
  const setMobileNavOpen = useUiStore((state) => state.setMobileNavOpen);
  const setCommandOpen = useUiStore((state) => state.setCommandOpen);

  return (
    <header className="sticky top-0 z-30 flex h-12 shrink-0 items-center gap-2 border-b border-border bg-background/85 px-3 backdrop-blur-md supports-[backdrop-filter]:bg-background/70 sm:px-4">
      <Button
        variant="ghost"
        size="icon-sm"
        className="md:hidden"
        aria-label="Open navigation"
        onClick={() => setMobileNavOpen(true)}
      >
        <Menu />
      </Button>

      <Breadcrumb className="min-w-0 flex-1">
        <BreadcrumbList>
          {crumbs.map((crumb, index) => {
            const last = index === crumbs.length - 1;
            return (
              <React.Fragment key={index}>
                {index > 0 ? <BreadcrumbSeparator /> : null}
                <BreadcrumbItem className={index < crumbs.length - 2 ? "hidden sm:inline-flex" : undefined}>
                  {last || !crumb.href ? (
                    <BreadcrumbPage className={!last ? "font-normal text-muted-foreground" : undefined}>
                      {crumb.label}
                    </BreadcrumbPage>
                  ) : (
                    <BreadcrumbLink asChild>
                      <Link href={crumb.href}>{crumb.label}</Link>
                    </BreadcrumbLink>
                  )}
                </BreadcrumbItem>
              </React.Fragment>
            );
          })}
        </BreadcrumbList>
      </Breadcrumb>

      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={() => setCommandOpen(true)}
          className="focus-ring hidden h-8 items-center gap-2 rounded-md border border-border bg-background pl-2.5 pr-1.5 text-[13px] text-muted-foreground shadow-xs transition-colors hover:border-border-strong hover:text-foreground sm:flex"
        >
          <Search className="size-3.5" />
          <span className="w-36 text-left lg:w-48">Search or jump to…</span>
          <Kbd>⌘K</Kbd>
        </button>
        <Button
          variant="ghost"
          size="icon-sm"
          className="sm:hidden"
          aria-label="Search"
          onClick={() => setCommandOpen(true)}
        >
          <Search />
        </Button>
        <NotificationsPopover />
        <div className="ml-1">
          <UserMenu profileHref={orgPath(orgId, "/profile")} />
        </div>
      </div>
    </header>
  );
}
