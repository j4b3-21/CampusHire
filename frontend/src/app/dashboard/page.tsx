"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Building2, CalendarClock, ShieldCheck, UserCircle2 } from "lucide-react";

import { AppShell } from "@/components/layout/AppShell";
import { LoadingScreen } from "@/components/common/loading-screen";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { useCurrentUserQuery } from "@/hooks/use-current-user";
import { useAuth } from "@/hooks/use-auth";
import { ProtectedGuard } from "@/components/auth/AuthGuard";

export default function DashboardPage() {
  const router = useRouter();
  const { authenticated, loading, user, roles, logout } = useAuth();
  const currentUserQuery = useCurrentUserQuery(authenticated);

  React.useEffect(() => {
    if (!loading && !authenticated) {
      router.replace("/login");
    }
  }, [authenticated, loading, router]);

  if (loading || currentUserQuery.isLoading) {
    return <LoadingScreen label="Loading dashboard..." />;
  }

  if (!authenticated || !user) {
    return <LoadingScreen label="Redirecting..." />;
  }

  return (
    <ProtectedGuard>
      <AppShell>
        <div className="space-y-6">
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          <Card className="border-white/10 bg-white/5">
            <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">User</CardTitle>
              <UserCircle2 className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-semibold">{user.firstName} {user.lastName}</div>
              <p className="text-xs text-muted-foreground">{user.username}</p>
            </CardContent>
          </Card>

          <Card className="border-white/10 bg-white/5">
            <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Email</CardTitle>
              <Building2 className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="text-lg font-semibold">{user.email}</div>
              <p className="text-xs text-muted-foreground">Verified through Keycloak</p>
            </CardContent>
          </Card>

          <Card className="border-white/10 bg-white/5">
            <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Roles</CardTitle>
              <ShieldCheck className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent className="flex flex-wrap gap-2">
              {roles.map((role) => (
                <Badge key={role}>{role}</Badge>
              ))}
            </CardContent>
          </Card>

          <Card className="border-white/10 bg-white/5">
            <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Last login</CardTitle>
              <CalendarClock className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="text-lg font-semibold">{user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString() : "—"}</div>
              <p className="text-xs text-muted-foreground">Synced from backend /auth/me</p>
            </CardContent>
          </Card>
        </div>

        <div className="grid gap-6 xl:grid-cols-[1.5fr_1fr]">
          <Card className="border-white/10 bg-white/5">
            <CardHeader>
              <CardTitle>CampusHire workspace</CardTitle>
              <CardDescription>Role-aware shell ready for institute, placement, and recruiter workflows.</CardDescription>
            </CardHeader>
            <CardContent className="grid gap-4 sm:grid-cols-2">
              {[
                "Placement drives",
                "Student profiles",
                "Recruiter approvals",
                "Shortlists and offers",
              ].map((item) => (
                <div key={item} className="rounded-2xl border border-border/70 bg-background/60 p-4">
                  <p className="text-sm font-medium">{item}</p>
                  <p className="mt-1 text-sm text-muted-foreground">Add your CRM workflows here without changing auth.</p>
                </div>
              ))}
            </CardContent>
          </Card>

          <Card className="border-white/10 bg-white/5">
            <CardHeader>
              <CardTitle>Account status</CardTitle>
              <CardDescription>Session and profile sync details.</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              <div className="flex items-center justify-between">
                <span>Status</span>
                <Badge>{user.active ? "Active" : "Inactive"}</Badge>
              </div>
              <div className="flex items-center justify-between">
                <span>Profile</span>
                <span className="text-muted-foreground">{user.profilePicture ? "Image present" : "No image"}</span>
              </div>
              <div className="flex items-center justify-between">
                <span>Institute</span>
                <span className="text-muted-foreground">{user.instituteId ?? "Not assigned"}</span>
              </div>
              <div className="pt-2">
                <Button className="w-full" variant="outline" onClick={() => void logout()}>
                  Sign out
                </Button>
              </div>
            </CardContent>
          </Card>
        </div>
        </div>
      </AppShell>
    </ProtectedGuard>
  );
}
