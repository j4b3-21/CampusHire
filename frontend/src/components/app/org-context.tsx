"use client";

import * as React from "react";

import { useModules, useMyAccess, useOrganizations } from "@/lib/api";
import { NAV_GROUPS, moduleIcon, type ModuleNavItem } from "@/lib/navigation";
import type { Persona } from "@/types/campushire";
import type { ModuleDefinition } from "@/types/metadata";
import type { ModuleCapabilities, MyAccess, Organization } from "@/types/org";

const FULL_ACCESS: ModuleCapabilities = {
  read: true,
  create: true,
  update: true,
  delete: true,
  readScope: "ALL",
  hiddenFields: [],
  readOnlyFields: [],
};

const NO_ACCESS: ModuleCapabilities = {
  read: false,
  create: false,
  update: false,
  delete: false,
  readScope: "NONE",
  hiddenFields: [],
  readOnlyFields: [],
};

export interface NavGroup {
  key: string;
  label: string;
  items: (ModuleNavItem & { module: ModuleDefinition })[];
}

export interface OrgContextValue {
  orgId: string;
  organization: Organization | undefined;
  organizations: Organization[];
  /** `null` when `/me/access` is not available; capabilities then fall back to the membership role. */
  access: MyAccess | null | undefined;
  modules: ModuleDefinition[];
  isAdmin: boolean;
  roleLabel: string;
  loading: boolean;
  capabilities: (moduleKey: string) => ModuleCapabilities;
  moduleByKey: (moduleKey: string) => ModuleDefinition | undefined;
  readableModules: ModuleDefinition[];
  navGroups: NavGroup[];
  /** CampusHire persona from the org role (owners/admins are `admin`). */
  persona: Persona;
  /** Admins, placement officers and coordinators. */
  isStaff: boolean;
  /** First value of a membership attribute (e.g. the recruiter's `company`). */
  attribute: (key: string) => string | undefined;
}

const PERSONA_ROLES: readonly Persona[] = ["tpo", "coordinator", "student", "recruiter"];

export function personaOf(isAdmin: boolean, roleKey: string | null | undefined): Persona {
  if (isAdmin) return "admin";
  return PERSONA_ROLES.find((persona) => persona === roleKey) ?? "member";
}

const OrgContext = React.createContext<OrgContextValue | null>(null);

export function useOrg() {
  const value = React.useContext(OrgContext);
  if (!value) throw new Error("useOrg must be used inside <OrgProvider>");
  return value;
}

const ROLE_LABELS: Record<string, string> = { OWNER: "Owner", ADMIN: "Admin", MEMBER: "Member" };

export function OrgProvider({ orgId, children }: { orgId: string; children: React.ReactNode }) {
  const orgs = useOrganizations();
  const access = useMyAccess(orgId);
  const modulesQuery = useModules(orgId);

  const value = React.useMemo<OrgContextValue>(() => {
    const organizations = orgs.data ?? [];
    const organization = organizations.find((org) => org.id === orgId);
    const accessData = access.data;
    const membershipRole = accessData?.membershipRole ?? organization?.role;
    const isAdmin = accessData ? accessData.admin : membershipRole === "OWNER" || membershipRole === "ADMIN";
    const modules = (modulesQuery.data ?? []).filter((module) => module.status !== "ARCHIVED");

    const capabilities = (moduleKey: string): ModuleCapabilities => {
      if (isAdmin) return FULL_ACCESS;
      if (accessData) return accessData.modules[moduleKey] ?? NO_ACCESS;
      // No access document (endpoint not deployed): members keep ADR-002 behavior; the API still enforces.
      return FULL_ACCESS;
    };

    const readableModules = modules.filter((module) => capabilities(module.apiKey).read);
    const readable = new Map(readableModules.map((module) => [module.apiKey, module]));
    const grouped = new Set<string>();
    const navGroups: NavGroup[] = NAV_GROUPS.map((group) => ({
      key: group.key,
      label: group.label,
      items: group.modules.flatMap((item) => {
        const definition = readable.get(item.moduleKey);
        if (!definition) return [];
        grouped.add(item.moduleKey);
        return [{ ...item, label: definition.labelPlural || item.label, module: definition }];
      }),
    })).filter((group) => group.items.length > 0);

    const others = readableModules.filter((module) => !grouped.has(module.apiKey));
    if (others.length > 0) {
      navGroups.push({
        key: "other",
        label: "More",
        items: others.map((module) => ({
          moduleKey: module.apiKey,
          label: module.labelPlural,
          icon: moduleIcon(module.apiKey),
          module,
        })),
      });
    }

    const persona = personaOf(isAdmin, accessData?.role?.key);
    const attribute = (key: string) => {
      const value = accessData?.attributes[key];
      if (typeof value === "string") return value;
      if (Array.isArray(value)) return value.find((item): item is string => typeof item === "string");
      return undefined;
    };

    return {
      orgId,
      organization,
      organizations,
      access: accessData,
      modules,
      isAdmin,
      roleLabel: accessData?.role?.label ?? ROLE_LABELS[membershipRole ?? ""] ?? "Member",
      loading: orgs.isLoading || access.isLoading || modulesQuery.isLoading,
      capabilities,
      moduleByKey: (moduleKey) => modules.find((module) => module.apiKey === moduleKey),
      readableModules,
      navGroups,
      persona,
      isStaff: persona === "admin" || persona === "tpo" || persona === "coordinator",
      attribute,
    };
  }, [orgId, orgs.data, orgs.isLoading, access.data, access.isLoading, modulesQuery.data, modulesQuery.isLoading]);

  return <OrgContext.Provider value={value}>{children}</OrgContext.Provider>;
}
