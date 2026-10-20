"use client";

import { ExternalLink } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { ErrorState } from "@/components/common/error-state";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { Avatar, AvatarFallback, initials } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useAuth } from "@/hooks/use-auth";
import { useMe } from "@/lib/api";
import { accountUrl } from "@/lib/auth/keycloak";

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid gap-1 py-3 sm:grid-cols-[10rem_1fr] sm:gap-4">
      <dt className="text-[13px] text-muted-foreground">{label}</dt>
      <dd className="min-w-0 text-[13px]">{children}</dd>
    </div>
  );
}

export function Profile() {
  const me = useMe();
  const { profile } = useAuth();
  const { organization, roleLabel, access } = useOrg();
  const name = me.data?.displayName ?? profile?.name ?? profile?.email ?? "You";

  return (
    <>
      <PageHeader
        title="Profile"
        description="How you appear in CampusHire. Your name and password are managed by your sign-in account."
        actions={
          <Button variant="outline" asChild>
            <a href={accountUrl()} target="_blank" rel="noreferrer">
              <ExternalLink /> Manage account
            </a>
          </Button>
        }
      />
      <PageBody className="max-w-3xl">
        {me.isError ? (
          <ErrorState error={me.error} onRetry={() => void me.refetch()} />
        ) : (
          <Card>
            <CardHeader className="flex-row items-center gap-3 pb-1">
              <Avatar className="size-10">
                <AvatarFallback>{initials(name)}</AvatarFallback>
              </Avatar>
              <div className="min-w-0">
                <CardTitle className="truncate text-[15px]">{me.isLoading ? <Skeleton className="h-4 w-32" /> : name}</CardTitle>
                <p className="truncate text-[13px] text-muted-foreground">{me.data?.email ?? profile?.email}</p>
              </div>
            </CardHeader>
            <CardContent className="pt-2">
              <dl className="divide-y divide-border">
                <Row label="Institute">{organization?.name}</Row>
                <Row label="Role">
                  <span className="flex flex-wrap items-center gap-1.5">
                    <Badge>{roleLabel}</Badge>
                    {access?.membershipRole ? <Badge variant="outline">{access.membershipRole.toLowerCase()}</Badge> : null}
                  </span>
                </Row>
                <Row label="Email verified">{profile?.emailVerified ? "Yes" : "No"}</Row>
                <Row label="Time zone">{me.data?.profile.timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone}</Row>
                <Row label="User ID">
                  <code className="font-mono text-xs text-muted-foreground">{me.data?.id ?? "–"}</code>
                </Row>
              </dl>
            </CardContent>
          </Card>
        )}
      </PageBody>
    </>
  );
}
