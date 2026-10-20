"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Building, LogOut, Monitor, Moon, PanelLeft, Plus, Sun } from "lucide-react";
import { useTheme } from "next-themes";

import { useOrg } from "@/components/app/org-context";
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
  CommandShortcut,
} from "@/components/ui/command";
import { useAuth } from "@/hooks/use-auth";
import {
  ADMIN_NAV,
  ANALYTICS_NAV,
  DASHBOARD_NAV,
  NOTIFICATIONS_NAV,
  STUDENT_NAV,
  modulePath,
  orgPath,
  recruiterNav,
} from "@/lib/navigation";
import { ModuleGlyph } from "@/components/common/module-glyph";
import { useUiStore } from "@/store/ui-store";

export function CommandPalette() {
  const router = useRouter();
  const open = useUiStore((state) => state.commandOpen);
  const setOpen = useUiStore((state) => state.setCommandOpen);
  const toggleSidebar = useUiStore((state) => state.toggleSidebar);
  const { orgId, organizations, navGroups, isAdmin, isStaff, capabilities, persona, attribute } = useOrg();
  const portal = persona === "student" ? STUDENT_NAV : persona === "recruiter" ? recruiterNav(attribute("company")) : null;
  const readableModules = portal ? [] : navGroups.flatMap((group) => group.items.map((item) => item.module));
  const { setTheme } = useTheme();
  const { logout } = useAuth();

  React.useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!(event.metaKey || event.ctrlKey) || event.altKey) return;
      const key = event.key.toLowerCase();
      if (key === "k") {
        event.preventDefault();
        setOpen(!useUiStore.getState().commandOpen);
      } else if (key === "b") {
        event.preventDefault();
        toggleSidebar();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [setOpen, toggleSidebar]);

  const run = (action: () => void) => {
    setOpen(false);
    action();
  };

  const creatable = readableModules.filter((module) => capabilities(module.apiKey).create);
  const otherOrgs = organizations.filter((org) => org.id !== orgId);

  return (
    <CommandDialog open={open} onOpenChange={setOpen}>
      <CommandInput placeholder="Search pages and actions…" />
      <CommandList>
        <CommandEmpty>Nothing matches that search.</CommandEmpty>
        <CommandGroup heading="Go to">
          {portal ? (
            portal.map((item) => (
              <CommandItem
                key={item.key}
                value={`go ${item.label}`}
                onSelect={() => run(() => router.push(orgPath(orgId, item.path)))}
              >
                <item.icon /> {item.label}
              </CommandItem>
            ))
          ) : (
            <CommandItem value="dashboard home overview" onSelect={() => run(() => router.push(orgPath(orgId)))}>
              <DASHBOARD_NAV.icon /> Dashboard
            </CommandItem>
          )}
          {isStaff ? (
            <CommandItem
              value="analytics placement report charts"
              onSelect={() => run(() => router.push(orgPath(orgId, ANALYTICS_NAV.path)))}
            >
              <ANALYTICS_NAV.icon /> {ANALYTICS_NAV.label}
            </CommandItem>
          ) : null}
          <CommandItem
            value="notifications inbox alerts"
            onSelect={() => run(() => router.push(orgPath(orgId, NOTIFICATIONS_NAV.path)))}
          >
            <NOTIFICATIONS_NAV.icon /> {NOTIFICATIONS_NAV.label}
          </CommandItem>
          {readableModules.map((definition) => (
            <CommandItem
              key={definition.apiKey}
              value={`go ${definition.labelPlural} ${definition.apiKey}`}
              onSelect={() => run(() => router.push(modulePath(orgId, definition.apiKey)))}
            >
              <ModuleGlyph moduleKey={definition.apiKey} /> {definition.labelPlural}
            </CommandItem>
          ))}
          {isAdmin
            ? ADMIN_NAV.map((item) => (
                <CommandItem
                  key={item.section}
                  value={`admin ${item.label}`}
                  onSelect={() => run(() => router.push(orgPath(orgId, `/admin/${item.section}`)))}
                >
                  <item.icon /> {item.label}
                </CommandItem>
              ))
            : null}
        </CommandGroup>

        {creatable.length > 0 ? (
          <>
            <CommandSeparator />
            <CommandGroup heading="Create">
              {creatable.map((module) => (
                <CommandItem
                  key={module.apiKey}
                  value={`new create add ${module.labelSingular}`}
                  onSelect={() => run(() => router.push(modulePath(orgId, module.apiKey, "/new")))}
                >
                  <Plus /> New {module.labelSingular.toLowerCase()}
                </CommandItem>
              ))}
            </CommandGroup>
          </>
        ) : null}

        {otherOrgs.length > 0 ? (
          <>
            <CommandSeparator />
            <CommandGroup heading="Switch institute">
              {otherOrgs.map((org) => (
                <CommandItem
                  key={org.id}
                  value={`switch institute ${org.name}`}
                  onSelect={() => run(() => router.push(orgPath(org.id)))}
                >
                  <Building /> {org.name}
                </CommandItem>
              ))}
            </CommandGroup>
          </>
        ) : null}

        <CommandSeparator />
        <CommandGroup heading="Preferences">
          <CommandItem value="theme light mode" onSelect={() => run(() => setTheme("light"))}>
            <Sun /> Light theme
          </CommandItem>
          <CommandItem value="theme dark mode" onSelect={() => run(() => setTheme("dark"))}>
            <Moon /> Dark theme
          </CommandItem>
          <CommandItem value="theme system" onSelect={() => run(() => setTheme("system"))}>
            <Monitor /> Match system theme
          </CommandItem>
          <CommandItem value="toggle sidebar" onSelect={() => run(toggleSidebar)}>
            <PanelLeft /> Toggle sidebar
            <CommandShortcut>⌘B</CommandShortcut>
          </CommandItem>
          <CommandItem value="sign out log out" onSelect={() => run(() => void logout())}>
            <LogOut /> Sign out
          </CommandItem>
        </CommandGroup>
      </CommandList>
    </CommandDialog>
  );
}
