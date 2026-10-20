import type { Instant, Uuid } from "@/types/api";

/** Common filters of every analytics endpoint and the placement report. */
export interface AnalyticsFilters {
  batchId?: Uuid;
  departmentId?: Uuid;
  /** ISO date, inclusive. */
  from?: string;
  /** ISO date, inclusive. */
  to?: string;
}

export interface AnalyticsOverview {
  totalStudents: number;
  participatingStudents: number;
  placedStudents: number;
  /** 0..1 */
  placementRate: number;
  offers: number;
  acceptedOffers: number;
  highestCtc: number | null;
  averageCtc: number | null;
  medianCtc: number | null;
  activeDrives: number;
  applications: number;
  companiesVisited: number;
  refreshedAt: Instant;
}

export interface DepartmentStats {
  departmentId: Uuid | null;
  departmentName: string | null;
  totalStudents: number;
  participatingStudents: number;
  placedStudents: number;
  placementRate: number;
  offers: number;
  highestCtc: number | null;
  averageCtc: number | null;
  medianCtc: number | null;
}

export interface AnalyticsDepartments {
  departments: DepartmentStats[];
  refreshedAt: Instant;
}

export interface CompanyStats {
  companyId: Uuid;
  companyName: string | null;
  offers: number;
  highestCtc: number | null;
  averageCtc: number | null;
  drives: number;
}

export interface AnalyticsCompanies {
  companies: CompanyStats[];
  refreshedAt: Instant;
}

export interface CtcBucket {
  label: string;
  minLpa: number;
  maxLpa: number | null;
  count: number;
}

export interface CtcDistribution {
  unit: "LPA";
  offers: number;
  buckets: CtcBucket[];
  refreshedAt: Instant;
}

export interface TrendPoint {
  /** `yyyy-MM` */
  period: string;
  offers: number;
  applications: number;
}

export interface AnalyticsTrend {
  interval: "month";
  points: TrendPoint[];
  refreshedAt: Instant;
}

export interface FunnelStage {
  stage: string;
  count: number;
  reached: number;
  conversionRate: number;
}

export interface DriveFunnel {
  driveId: Uuid;
  driveTitle: string | null;
  companyId: Uuid | null;
  companyName: string | null;
  applications: number;
  stages: FunnelStage[];
  offerRate: number;
  refreshedAt: Instant;
}

export type ReportFormat = "xlsx" | "pdf";
