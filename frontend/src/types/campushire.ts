import type { Instant, Uuid } from "@/types/api";
import type { ModuleCapabilities, MembershipRole } from "@/types/org";

/** ISO local date, e.g. `2026-11-02`. */
export type LocalDate = string;

export type CampusRoleKey = "tpo" | "coordinator" | "student" | "recruiter";

/** Who the caller is inside the institute; drives navigation and the dashboard. */
export type Persona = "admin" | "tpo" | "coordinator" | "student" | "recruiter" | "member";

export type DriveStatus = "draft" | "published" | "registration_closed" | "in_progress" | "completed" | "cancelled";

export type ApplicationStage =
  | "applied"
  | "shortlisted"
  | "assessment"
  | "interview"
  | "offered"
  | "joined"
  | "rejected"
  | "withdrawn";

export type OfferStatus = "extended" | "accepted" | "declined" | "revoked";
export type OfferType = "full_time" | "internship" | "ppo";
export type PlacementStatus = "unplaced" | "placed" | "opted_out" | "higher_studies";

export const ELIGIBILITY_REASON_CODES = [
  "not_participating",
  "cgpa_below_minimum",
  "too_many_backlogs",
  "department_not_eligible",
  "batch_not_eligible",
  "program_not_eligible",
  "tenth_below_minimum",
  "twelfth_below_minimum",
  "offer_limit_reached",
  "eligibility_invalid",
] as const;

export type EligibilityReasonCode = (typeof ELIGIBILITY_REASON_CODES)[number];

/** Drive field `eligibility`; absent rules do not restrict. */
export interface EligibilityRules {
  minCgpa?: number;
  maxActiveBacklogs?: number;
  departments?: Uuid[];
  batches?: Uuid[];
  programs?: string[];
  minTenth?: number;
  minTwelfth?: number;
  excludePlaced?: boolean;
}

export interface EligibilityReason {
  code: EligibilityReasonCode | string;
  message: string;
}

export interface EligibilityVerdict {
  eligible: boolean;
  reasons: EligibilityReason[];
}

/** `GET /orgs/{orgId}/campushire/me`. */
export interface CampusHireMe {
  organizationId: Uuid;
  membershipRole: MembershipRole;
  admin: boolean;
  role: { key: string; label: string } | null;
  student: {
    id: Uuid;
    rollNumber: string;
    fullName: string;
    email: string;
    departmentId: Uuid | null;
    batchId: Uuid | null;
    program: string | null;
    placementStatus: PlacementStatus | string | null;
    profileVerified: boolean;
    version: number;
  } | null;
  departmentIds: Uuid[];
  companyIds: Uuid[];
  attributes: Record<string, string | string[]>;
  capabilities: Record<string, ModuleCapabilities>;
  onboarding: {
    packInstalled: boolean;
    packVersion: number | null;
    status: "setup_required" | "student_not_linked" | "complete";
  };
}

// ---- Onboarding ----

export interface OnboardingOrganization {
  organizationId: Uuid;
  name: string;
  member: boolean;
  roleKey: string | null;
  linked: boolean;
  studentRecordFound: boolean;
}

/** `GET /campushire/onboarding`. */
export interface OnboardingStatus {
  email: string | null;
  emailVerified: boolean;
  domain: string | null;
  organizations: OnboardingOrganization[];
}

export interface ClaimResult {
  organizationId: Uuid;
  studentId: Uuid;
  roleKey: string;
  attributes: Record<string, string>;
  alreadyLinked: boolean;
}

// ---- Dashboard ----

export interface UpcomingDrive {
  id: Uuid;
  title: string;
  companyId: Uuid | null;
  companyName: string | null;
  driveStatus: DriveStatus;
  driveDate: LocalDate | null;
  registrationClosesAt: Instant | null;
}

export interface FollowUpItem {
  id: Uuid;
  title: string;
  companyId: Uuid | null;
  dueDate: LocalDate;
  overdue: boolean;
}

export interface StaffDashboard {
  role: "tpo" | "admin" | "coordinator";
  departmentIds: Uuid[] | null;
  counts: { students: number; openDrives: number; applicationsThisWeek: number; activeOffers: number };
  companiesByStage: Record<string, number>;
  placement: {
    totalStudents: number;
    participatingStudents: number;
    placedStudents: number;
    placementPercentage: number | null;
    offers: number;
    highestCtc: number | null;
    averageCtc: number | null;
    refreshedAt: Instant | null;
  };
  upcomingDrives: UpcomingDrive[];
  followUps: { dueToday: number; overdue: number; items: FollowUpItem[] } | null;
  generatedAt: Instant;
}

export interface StudentDashboardOffer {
  id: Uuid;
  companyId: Uuid | null;
  companyName: string | null;
  ctc: number | null;
  offerType: OfferType | string | null;
  offerStatus: OfferStatus | string;
  respondBy: LocalDate | null;
}

export interface StudentDashboard {
  role: "student";
  student: {
    id: Uuid;
    rollNumber: string;
    fullName: string;
    placementStatus: PlacementStatus | string | null;
    profileVerified: boolean;
  } | null;
  profile: { completeness: number; missingFields: string[] } | null;
  eligibleOpenDrives: number;
  applicationsByStage: Partial<Record<ApplicationStage | string, number>>;
  offers: StudentDashboardOffer[];
  generatedAt: Instant;
}

export interface RecruiterDrive {
  id: Uuid;
  title: string;
  companyId: Uuid | null;
  driveStatus: DriveStatus;
  driveDate: LocalDate | null;
  registrationClosesAt: Instant | null;
  applicants: number;
  applicantsByStage: Partial<Record<ApplicationStage | string, number>>;
}

export interface RecruiterDashboard {
  role: "recruiter";
  companyIds: Uuid[];
  drives: RecruiterDrive[];
  generatedAt: Instant;
}

export interface NoRoleDashboard {
  role: null;
  generatedAt: Instant;
}

export type CampusDashboard = StaffDashboard | StudentDashboard | RecruiterDashboard | NoRoleDashboard;

// ---- Drives (staff) ----

export interface EligibilityPreview {
  driveId: Uuid;
  eligibleCount: number;
  totalConsidered: number;
  sample: { studentId: Uuid; rollNumber: string; fullName: string; departmentId: Uuid | null; cgpa: number | null }[];
  breakdown: { byDepartment: { departmentId: Uuid | null; eligible: number; considered: number }[] };
  reasons: Partial<Record<EligibilityReasonCode | string, number>>;
  driveCtc: number | null;
}

export type DriveAction = "publish" | "close" | "start" | "complete" | "cancel";

/** Lifecycle response (`ETag` = version). */
export interface DriveState {
  id: Uuid;
  version: number;
  title: string;
  driveStatus: DriveStatus;
  registrationOpensAt: Instant | null;
  registrationClosesAt: Instant | null;
  notifiedStudents?: number | null;
}

// ---- Students ----

export type StudentDriveFilter = "open" | "upcoming" | "all";

export interface StudentDrive {
  id: Uuid;
  title: string;
  driveStatus: DriveStatus;
  company: { id: Uuid; name: string } | null;
  job: { id: Uuid; title: string; jobType: string | null; ctc: number | null; location: string | null } | null;
  registrationOpensAt: Instant | null;
  registrationClosesAt: Instant | null;
  driveDate: LocalDate | null;
  mode: string | null;
  venue: string | null;
  description: string | null;
  registrationOpen: boolean;
  eligibility: EligibilityVerdict | null;
  application: { id: Uuid; stage: ApplicationStage | string } | null;
}

export interface ApplicationView {
  id: Uuid;
  driveId: Uuid;
  studentId: Uuid;
  stage: ApplicationStage | string;
  version: number;
  createdAt: Instant;
}

// ---- Applicants ----

export interface Applicant {
  applicationId: Uuid;
  version: number;
  stage: ApplicationStage | string;
  appliedAt: Instant;
  updatedAt: Instant;
  student: {
    id: Uuid;
    rollNumber: string;
    fullName: string;
    email: string | null;
    departmentId: Uuid | null;
    batchId: Uuid | null;
    program: string | null;
    cgpa: number | null;
    activeBacklogs: number | null;
    totalBacklogs: number | null;
    tenthPercentage: number | null;
    twelfthPercentage: number | null;
    placementStatus: PlacementStatus | string | null;
    skills: string | null;
    resume: { fileId: Uuid; name: string | null } | null;
  };
  eligibleNow: EligibilityVerdict | null;
}

export type ApplicantSort = "rollNumber" | "fullName" | "cgpa" | "appliedAt" | "updatedAt" | "stage";

export interface ApplicantQuery {
  stage?: string;
  q?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export interface PipelineResultItem {
  applicationId: Uuid | null;
  rollNumber: string | null;
  ok: boolean;
  stage: string | null;
  version: number | null;
  error: { code: string; message: string } | null;
}

export interface PipelineResult {
  results: PipelineResultItem[];
  succeeded: number;
  failed: number;
  unknownRollNumbers: string[];
}

// ---- Offers ----

export interface OfferView {
  id: Uuid;
  version: number;
  applicationId: Uuid;
  studentId: Uuid;
  companyId: Uuid;
  driveId: Uuid | null;
  departmentId: Uuid | null;
  ctc: number;
  currency: string;
  offerType: OfferType | string | null;
  joiningDate: LocalDate | null;
  respondBy: LocalDate | null;
  offerLetterFileId: Uuid | null;
  offerStatus: OfferStatus | string;
  applicationStage: string | null;
  placementStatus: string | null;
  policyOverridden: boolean;
}
