"use client";

import * as React from "react";

import type { OrgContextValue } from "@/components/app/org-context";
import { ApplicantsPanel } from "@/components/drives/applicants-panel";
import { DriveLifecyclePanel } from "@/components/drives/drive-lifecycle";
import { EligibilityEditor } from "@/components/drives/eligibility-editor";
import type { VersionedRecord } from "@/lib/api";
import type { ModuleDefinition } from "@/types/metadata";

export interface ExtensionTab {
  key: string;
  label: string;
  content: React.ReactNode;
}

export interface RecordExtensions {
  tabs: ExtensionTab[];
  /** Rendered at the top of the detail sidebar. */
  aside: React.ReactNode;
}

const NONE: RecordExtensions = { tabs: [], aside: null };

/** Domain panels that enrich the generic record page (CampusHire drives: lifecycle, eligibility, applicants). */
export function recordExtensions(
  module: ModuleDefinition,
  record: VersionedRecord,
  org: Pick<OrgContextValue, "isStaff" | "persona" | "capabilities">
): RecordExtensions {
  if (module.apiKey !== "drive" || record.deletedAt) return NONE;
  const driveCaps = org.capabilities("drive");
  const canManage = org.isStaff && driveCaps.update;
  const tabs: ExtensionTab[] = [];
  if (org.isStaff) {
    tabs.push({ key: "eligibility", label: "Eligibility", content: <EligibilityEditor record={record} canEdit={canManage} /> });
  }
  if ((org.isStaff || org.persona === "recruiter") && org.capabilities("application").read) {
    const company = typeof record.data.company === "string" ? record.data.company : null;
    tabs.push({ key: "applicants", label: "Applicants", content: <ApplicantsPanel driveId={record.id} companyId={company} /> });
  }
  const aside = org.isStaff || org.persona === "recruiter" ? <DriveLifecyclePanel record={record} canManage={canManage} /> : null;
  return { tabs, aside };
}
