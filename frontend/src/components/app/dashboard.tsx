"use client";

import { useOrg } from "@/components/app/org-context";
import { RecruiterDashboard } from "@/components/dashboard/recruiter-dashboard";
import { StaffDashboard } from "@/components/dashboard/staff-dashboard";
import { StudentDashboard } from "@/components/dashboard/student-dashboard";
import { Skeleton } from "@/components/ui/skeleton";

/** Role dashboard: staff (admins, placement officers, coordinators), students and recruiters. */
export function Dashboard() {
  const { persona, loading } = useOrg();

  if (loading) {
    return (
      <div className="flex flex-col gap-6 px-4 pt-6 sm:px-6 lg:px-8">
        <div className="flex flex-col gap-2">
          <Skeleton className="h-6 w-56" />
          <Skeleton className="h-4 w-72" />
        </div>
        <Skeleton className="h-28 w-full rounded-xl" />
        <div className="grid gap-6 lg:grid-cols-2">
          <Skeleton className="h-64 rounded-lg" />
          <Skeleton className="h-64 rounded-lg" />
        </div>
      </div>
    );
  }
  if (persona === "student") return <StudentDashboard />;
  if (persona === "recruiter") return <RecruiterDashboard />;
  return <StaffDashboard />;
}
