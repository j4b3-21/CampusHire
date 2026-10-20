"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { PanelLeftClose, PanelLeftOpen, Search } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { OrgSwitcher } from "@/components/layout/org-switcher";
import { Kbd } from "@/components/ui/kbd";
import { Sheet, SheetContent, SheetDescription, SheetTitle } from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { ADMIN_NAV, DASHBOARD_NAV, modulePath, orgPath } from "@/lib/navigation";
import { cn } from "@/lib/utils";
import { useUiStore } from "@/store/ui-store";

interface NavLinkProps {
  href: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  active: boolean;
  collapsed?: boolean;
  onNavigate?: () => void;
}

function NavLink({ href, label, icon: Icon, active, collapsed, onNavigate }: NavLinkProps) {
  const link = (
    <Link
      href={href}
      onClick={onNavigate}
      aria-current={active ? "page" : undefined}
      className={cn(
        "focus-ring group flex h-8 items-center gap-2.5 rounded-md px-2 text-[13px] font-medium transition-colors",
        active
          ? "bg-sidebar-active text-foreground"
          : "text-sidebar-foreground hover:bg-sidebar-active/70 hover:text-foreground",
        collapsed && "justify-center px-0"
      )}
    >
      <Icon
        className={cn(
          "size-4 shrink-0 transition-colors",
          active ? "text-primary" : "text-muted-foreground group-hover:text-foreground"
        )}
      />
      {!collapsed ? <span className="truncate">{label}</span> : <span className="sr-only">{label}</span>}
    </Link>
  );
  return (
    <SimpleTooltip label={label} side="right" disabled={!collapsed}>
      {link}
    </SimpleTooltip>
  );
}

function NavSection({ label, collapsed, children }: { label: string; collapsed?: boolean; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-px">
      {collapsed ? (
        <div className="mx-2 my-2 h-px bg-border" aria-hidden />
      ) : (
        <div className="px-2 pb-1 pt-4 text-[11px] font-medium text-subtle-foreground">{label}</div>
      )}
      {children}
    </div>
  );
}

function SidebarNav({ collapsed, onNavigate }: { collapsed?: boolean; onNavigate?: () => void }) {
  const pathname = usePathname();
  const { orgId, navGroups, isAdmin, loading } = useOrg();
  const dashboardHref = orgPath(orgId);

  return (
    <nav aria-label="Main" className="flex flex-col gap-px px-2 pb-4">
      <NavLink
        href={dashboardHref}
        label={DASHBOARD_NAV.label}
        icon={DASHBOARD_NAV.icon}
        active={pathname === dashboardHref}
        collapsed={collapsed}
        onNavigate={onNavigate}
      />
      {loading ? (
        <div className="flex flex-col gap-2 px-2 pt-5" aria-hidden>
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} className={cn("h-4", collapsed ? "w-4" : i % 3 === 0 ? "w-16" : "w-28")} />
          ))}
        </div>
      ) : (
        navGroups.map((group) => (
          <NavSection key={group.key} label={group.label} collapsed={collapsed}>
            {group.items.map((item) => {
              const href = modulePath(orgId, item.moduleKey);
              return (
                <NavLink
                  key={item.moduleKey}
                  href={href}
                  label={item.label}
                  icon={item.icon}
                  active={pathname === href || pathname.startsWith(`${href}/`)}
                  collapsed={collapsed}
                  onNavigate={onNavigate}
                />
              );
            })}
          </NavSection>
        ))
      )}
      {isAdmin ? (
        <NavSection label="Admin" collapsed={collapsed}>
          {ADMIN_NAV.map((item) => {
            const href = orgPath(orgId, `/admin/${item.section}`);
            return (
              <NavLink
                key={item.section}
                href={href}
                label={item.label}
                icon={item.icon}
                active={pathname === href}
                collapsed={collapsed}
                onNavigate={onNavigate}
              />
            );
          })}
        </NavSection>
      ) : null}
    </nav>
  );
}

function SearchButton({ collapsed }: { collapsed?: boolean }) {
  const setCommandOpen = useUiStore((state) => state.setCommandOpen);
  return (
    <SimpleTooltip label="Search" shortcut="⌘K" side="right" disabled={!collapsed}>
      <button
        type="button"
        onClick={() => setCommandOpen(true)}
        className={cn(
          "focus-ring flex h-8 w-full items-center gap-2 rounded-md border border-border bg-background px-2 text-[13px] text-muted-foreground shadow-xs transition-colors hover:border-border-strong hover:text-foreground",
          collapsed && "justify-center px-0"
        )}
      >
        <Search className="size-3.5 shrink-0" />
        {!collapsed ? (
          <>
            <span className="flex-1 text-left">Search…</span>
            <Kbd>⌘K</Kbd>
          </>
        ) : (
          <span className="sr-only">Search</span>
        )}
      </button>
    </SimpleTooltip>
  );
}

export function AppSidebar() {
  const collapsed = useUiStore((state) => state.sidebarCollapsed);
  const toggleSidebar = useUiStore((state) => state.toggleSidebar);
  const mobileOpen = useUiStore((state) => state.mobileNavOpen);
  const setMobileOpen = useUiStore((state) => state.setMobileNavOpen);

  return (
    <>
      <aside
        className={cn(
          "sticky top-0 hidden h-dvh shrink-0 flex-col border-r border-border bg-sidebar transition-[width] duration-200 ease-out md:flex",
          collapsed ? "w-[3.25rem]" : "w-60"
        )}
        aria-label="Sidebar"
      >
        <div className={cn("flex flex-col gap-2 p-2", collapsed && "items-center")}>
          <OrgSwitcher collapsed={collapsed} />
          <SearchButton collapsed={collapsed} />
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
          <SidebarNav collapsed={collapsed} />
        </div>
        <div className={cn("border-t border-border p-2", collapsed && "flex justify-center")}>
          <SimpleTooltip label={collapsed ? "Expand sidebar" : "Collapse sidebar"} shortcut="⌘B" side="right">
            <button
              type="button"
              onClick={toggleSidebar}
              aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
              className={cn(
                "focus-ring flex h-8 items-center gap-2 rounded-md px-2 text-[13px] text-muted-foreground transition-colors hover:bg-sidebar-active hover:text-foreground",
                collapsed ? "w-8 justify-center px-0" : "w-full"
              )}
            >
              {collapsed ? <PanelLeftOpen className="size-4" /> : <PanelLeftClose className="size-4" />}
              {!collapsed ? <span>Collapse</span> : null}
            </button>
          </SimpleTooltip>
        </div>
      </aside>

      <Sheet open={mobileOpen} onOpenChange={setMobileOpen}>
        <SheetContent side="left" className="w-72 bg-sidebar p-0" showClose={false}>
          <SheetTitle className="sr-only">Navigation</SheetTitle>
          <SheetDescription className="sr-only">Switch institute and open modules</SheetDescription>
          <div className="flex flex-col gap-2 p-2">
            <OrgSwitcher />
            <SearchButton />
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto">
            <SidebarNav onNavigate={() => setMobileOpen(false)} />
          </div>
        </SheetContent>
      </Sheet>
    </>
  );
}
