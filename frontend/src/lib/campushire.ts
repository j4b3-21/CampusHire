import { differenceInCalendarDays, differenceInHours, differenceInMinutes, isValid, parseISO } from "date-fns";

import type { Tone } from "@/lib/metadata/tones";
import type {
  CampusDashboard,
  DriveAction,
  DriveStatus,
  EligibilityReasonCode,
  RecruiterDashboard,
  StaffDashboard,
  StudentDashboard,
} from "@/types/campushire";

export function isStudentDashboard(data: CampusDashboard | undefined): data is StudentDashboard {
  return data?.role === "student";
}

export function isRecruiterDashboard(data: CampusDashboard | undefined): data is RecruiterDashboard {
  return data?.role === "recruiter";
}

export function isStaffDashboard(data: CampusDashboard | undefined): data is StaffDashboard {
  return data?.role === "tpo" || data?.role === "admin" || data?.role === "coordinator";
}

const LAKH = 100_000;
const lpaFormatter = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 2 });
const percentFormatter = new Intl.NumberFormat("en-IN", { style: "percent", maximumFractionDigits: 1 });
const inrFormatter = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 });

/** INR amount as lakh per annum, e.g. `₹12.5 LPA`. */
export function formatLpa(amount: number | string | null | undefined) {
  const value = typeof amount === "string" ? Number(amount) : amount;
  if (value === null || value === undefined || !Number.isFinite(value)) return "–";
  return `₹${lpaFormatter.format(value / LAKH)} LPA`;
}

export function formatInr(amount: number | null | undefined) {
  if (amount === null || amount === undefined || !Number.isFinite(amount)) return "–";
  return inrFormatter.format(amount);
}

/** `0.5714` -> `57.1%`. */
export function formatRate(rate: number | null | undefined) {
  if (rate === null || rate === undefined || !Number.isFinite(rate)) return "–";
  return percentFormatter.format(rate);
}

export const DRIVE_STATUS_LABELS: Record<DriveStatus, string> = {
  draft: "Draft",
  published: "Published",
  registration_closed: "Registration closed",
  in_progress: "In progress",
  completed: "Completed",
  cancelled: "Cancelled",
};

export const DRIVE_STATUS_TONES: Record<DriveStatus, Tone> = {
  draft: "neutral",
  published: "green",
  registration_closed: "amber",
  in_progress: "indigo",
  completed: "teal",
  cancelled: "red",
};

export function driveStatusLabel(status: string | null | undefined) {
  if (!status) return "Unknown";
  return DRIVE_STATUS_LABELS[status as DriveStatus] ?? status;
}

export interface DriveActionInfo {
  action: DriveAction;
  label: string;
  /** Statuses the action is allowed from. */
  from: DriveStatus[];
  to: DriveStatus;
  confirm: string;
  destructive?: boolean;
}

export const DRIVE_ACTIONS: DriveActionInfo[] = [
  {
    action: "publish",
    label: "Publish",
    from: ["draft"],
    to: "published",
    confirm: "Eligible students with a linked account are notified in the app and by email. The eligibility rules are checked first.",
  },
  {
    action: "close",
    label: "Close registration",
    from: ["published"],
    to: "registration_closed",
    confirm: "Students can no longer apply. Existing applications stay as they are.",
  },
  {
    action: "start",
    label: "Start drive",
    from: ["published", "registration_closed"],
    to: "in_progress",
    confirm: "Marks the drive as running. Registration closes if it's still open.",
  },
  {
    action: "complete",
    label: "Complete",
    from: ["registration_closed", "in_progress"],
    to: "completed",
    confirm: "Marks the drive as finished.",
  },
  {
    action: "cancel",
    label: "Cancel drive",
    from: ["draft", "published", "registration_closed", "in_progress"],
    to: "cancelled",
    confirm: "Applicants are notified that the drive was cancelled. This can't be undone.",
    destructive: true,
  },
];

export const REASON_LABELS: Record<EligibilityReasonCode, string> = {
  not_participating: "Not participating in placements",
  cgpa_below_minimum: "CGPA below minimum",
  too_many_backlogs: "Too many active backlogs",
  department_not_eligible: "Department not eligible",
  batch_not_eligible: "Batch not eligible",
  program_not_eligible: "Program not eligible",
  tenth_below_minimum: "10th % below minimum",
  twelfth_below_minimum: "12th % below minimum",
  offer_limit_reached: "Offer limit reached",
  eligibility_invalid: "Rules being edited",
};

export function reasonLabel(code: string) {
  return REASON_LABELS[code as EligibilityReasonCode] ?? code.replace(/_/g, " ");
}

/** Stage order of the application workflow (fallback when the workflow can't be read). */
export const APPLICATION_STAGES = [
  "applied",
  "shortlisted",
  "assessment",
  "interview",
  "offered",
  "joined",
  "rejected",
  "withdrawn",
] as const;

export const STAGE_LABELS: Record<string, string> = {
  applied: "Applied",
  shortlisted: "Shortlisted",
  assessment: "Assessment",
  interview: "Interview",
  offered: "Offered",
  joined: "Joined",
  rejected: "Rejected",
  withdrawn: "Withdrawn",
};

/** Chart colors per application stage (design tokens, light/dark aware). */
export const STAGE_CHART_COLORS: Record<string, string> = {
  applied: "var(--chart-muted)",
  shortlisted: "var(--chart-2)",
  assessment: "var(--chart-4)",
  interview: "var(--chart-5)",
  offered: "var(--chart-1)",
  joined: "var(--chart-3)",
  rejected: "var(--destructive)",
  withdrawn: "var(--border-strong)",
};

export function stageLabel(stage: string) {
  return STAGE_LABELS[stage] ?? stage.replace(/_/g, " ");
}

/** Stages a student may withdraw from (workflow transition `withdraw`). */
export const WITHDRAWABLE_STAGES = ["applied", "shortlisted"];

export interface Countdown {
  label: string;
  urgent: boolean;
  past: boolean;
}

/** "Closes in 3 days" style countdown to an instant. */
export function countdown(instant: string | null | undefined, verb = "Closes", now = new Date()): Countdown | null {
  if (!instant) return null;
  const date = parseISO(instant);
  if (!isValid(date)) return null;
  const minutes = differenceInMinutes(date, now);
  if (minutes <= 0) return { label: verb === "Closes" ? "Closed" : "Started", urgent: false, past: true };
  const hours = differenceInHours(date, now);
  const days = differenceInCalendarDays(date, now);
  if (minutes < 60) return { label: `${verb} in ${minutes} min`, urgent: true, past: false };
  if (hours < 24) return { label: `${verb} in ${hours} h`, urgent: true, past: false };
  if (days === 1) return { label: `${verb} tomorrow`, urgent: true, past: false };
  return { label: `${verb} in ${days} days`, urgent: days <= 3, past: false };
}
