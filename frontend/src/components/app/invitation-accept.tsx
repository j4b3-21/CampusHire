"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { toast } from "sonner";
import { AlertTriangle, Building2, CalendarClock, CheckCircle2, LogOut, Mail, MailWarning, ShieldCheck, TimerOff, XCircle } from "lucide-react";

import { BrandWordmark } from "@/components/common/brand";
import { ThemeToggle } from "@/components/common/theme-toggle";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { useAuth } from "@/hooks/use-auth";
import { ApiError, errorMessage, useAcceptInvitation, useInvitationPreview } from "@/lib/api";
import { accountUrl } from "@/lib/auth/keycloak";
import { formatDateTime, formatRelative } from "@/lib/metadata/format";
import { orgPath } from "@/lib/navigation";
import type { InvitationPreview } from "@/types/org";

type Problem = {
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  description: string;
  kind: "mismatch" | "unverified" | "expired" | "revoked" | "used" | "member" | "missing" | "other";
};

function problemFor(error: unknown): Problem {
  const code = error instanceof ApiError ? error.code : "";
  switch (code) {
    case "invitation_email_mismatch":
      return {
        icon: MailWarning,
        kind: "mismatch",
        title: "This invitation is for a different email address",
        description: "Sign in with the address the invitation was sent to. Your current session stays untouched until you choose to switch.",
      };
    case "email_not_verified":
      return {
        icon: MailWarning,
        kind: "unverified",
        title: "Verify your email address first",
        description: "Open your account settings, verify the address, then come back to this link.",
      };
    case "invitation_expired":
      return { icon: TimerOff, kind: "expired", title: "This invitation has expired", description: "Ask your placement cell to send a new one." };
    case "invitation_revoked":
      return { icon: XCircle, kind: "revoked", title: "This invitation was revoked", description: "Ask your placement cell if you still need access." };
    case "invitation_used":
      return { icon: CheckCircle2, kind: "used", title: "This invitation has already been used", description: "If it was you, the institute is already in your list." };
    case "already_member":
      return { icon: CheckCircle2, kind: "member", title: "You're already a member", description: "Open the institute to continue." };
    default:
      if (error instanceof ApiError && error.isNotFound) {
        return {
          icon: AlertTriangle,
          kind: "missing",
          title: "We couldn't find this invitation",
          description: "The link may be incomplete. Copy the whole link from the email and try again.",
        };
      }
      return { icon: AlertTriangle, kind: "other", title: "Something went wrong", description: errorMessage(error) };
  }
}

function problemForStatus(preview: InvitationPreview): Problem | null {
  switch (preview.status) {
    case "EXPIRED":
      return problemFor(new ApiError({ status: 410, code: "invitation_expired" }));
    case "REVOKED":
      return problemFor(new ApiError({ status: 410, code: "invitation_revoked" }));
    case "ACCEPTED":
      return problemFor(new ApiError({ status: 409, code: "invitation_used" }));
    default:
      return null;
  }
}

function Row({ icon: Icon, label, children }: { icon: React.ComponentType<{ className?: string }>; label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-start gap-3 py-2.5">
      <Icon className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
      <dt className="w-24 shrink-0 text-[13px] text-muted-foreground">{label}</dt>
      <dd className="min-w-0 flex-1 text-[13px] font-medium">{children}</dd>
    </div>
  );
}

export function InvitationAccept({ token }: { token: string }) {
  const router = useRouter();
  const { profile, logout } = useAuth();
  const preview = useInvitationPreview(token);
  const accept = useAcceptInvitation(token);
  const [acceptError, setAcceptError] = React.useState<unknown>(null);
  const next = `/invite/${encodeURIComponent(token)}`;

  const onAccept = async () => {
    setAcceptError(null);
    try {
      await accept.mutateAsync();
      toast.success(`Welcome to ${preview.data?.organizationName ?? "the institute"}`);
      if (preview.data) router.replace(orgPath(preview.data.organizationId));
    } catch (error) {
      setAcceptError(error);
    }
  };

  const problem = acceptError
    ? problemFor(acceptError)
    : preview.isError
      ? problemFor(preview.error)
      : preview.data
        ? problemForStatus(preview.data)
        : null;

  return (
    <div className="flex min-h-dvh flex-col bg-surface">
      <header className="flex h-14 items-center justify-between border-b border-border bg-background px-4 sm:px-6">
        <BrandWordmark />
        <ThemeToggle />
      </header>
      <main className="mx-auto flex w-full max-w-lg flex-1 flex-col justify-center px-4 py-12">
        <section aria-labelledby="invite-heading" className="rounded-xl border border-border bg-card p-6 shadow-xs sm:p-7">
          {preview.isLoading ? (
            <div className="flex flex-col gap-3" aria-busy>
              <Skeleton className="h-6 w-56" />
              <Skeleton className="h-4 w-72" />
              <Skeleton className="mt-4 h-32 w-full" />
            </div>
          ) : (
            <>
              <span className="grid size-10 place-items-center rounded-lg bg-primary-soft text-primary-soft-foreground">
                <Building2 className="size-5" />
              </span>
              <h1 id="invite-heading" className="mt-4 text-xl font-semibold tracking-[-0.02em]">
                {preview.data ? `Join ${preview.data.organizationName}` : "Invitation"}
              </h1>
              <p className="mt-1 text-[13px] text-muted-foreground">
                You&apos;ve been invited to CampusHire. Accepting adds the institute to your account.
              </p>

              {preview.data ? (
                <dl className="mt-5 divide-y divide-border border-y border-border">
                  <Row icon={ShieldCheck} label="Role">
                    {preview.data.roleLabel ??
                      preview.data.membershipRole.charAt(0) + preview.data.membershipRole.slice(1).toLowerCase()}
                  </Row>
                  <Row icon={Mail} label="Sent to">
                    <span className="font-mono text-xs">{preview.data.email}</span>
                  </Row>
                  <Row icon={CalendarClock} label="Expires">
                    <span title={formatDateTime(preview.data.expiresAt)}>{formatRelative(preview.data.expiresAt)}</span>
                  </Row>
                </dl>
              ) : null}

              {problem ? (
                <div
                  role="alert"
                  className="mt-5 flex gap-3 rounded-lg border border-warning/30 bg-warning-soft px-4 py-3 text-warning-soft-foreground"
                >
                  <problem.icon className="mt-0.5 size-4 shrink-0" />
                  <div className="text-[13px]">
                    <p className="font-medium">{problem.title}</p>
                    <p className="mt-0.5 opacity-90">{problem.description}</p>
                  </div>
                </div>
              ) : null}

              <div className="mt-6 flex flex-wrap items-center gap-2">
                {!problem && preview.data ? (
                  <Button onClick={() => void onAccept()} disabled={accept.isPending}>
                    {accept.isPending ? <Spinner className="text-primary-foreground" /> : <CheckCircle2 />}
                    Accept invitation
                  </Button>
                ) : null}
                {problem?.kind === "mismatch" ? (
                  <Button onClick={() => void logout({ next })}>
                    <LogOut /> Sign in with a different account
                  </Button>
                ) : null}
                {problem?.kind === "unverified" ? (
                  <Button asChild>
                    <a href={accountUrl()} target="_blank" rel="noreferrer">
                      Open account settings
                    </a>
                  </Button>
                ) : null}
                {(problem?.kind === "member" || problem?.kind === "used") && preview.data ? (
                  <Button asChild>
                    <Link href={orgPath(preview.data.organizationId)}>Open {preview.data.organizationName}</Link>
                  </Button>
                ) : null}
                <Button variant="ghost" asChild>
                  <Link href="/app">Go to my institutes</Link>
                </Button>
              </div>
              <p className="mt-6 border-t border-border pt-4 text-xs text-muted-foreground">
                Signed in as <span className="font-medium text-foreground">{profile?.email ?? profile?.username}</span>
                {problem?.kind !== "mismatch" ? (
                  <>
                    {" · "}
                    <button type="button" className="focus-ring rounded-sm text-primary hover:underline" onClick={() => void logout({ next })}>
                      Not you?
                    </button>
                  </>
                ) : null}
              </p>
            </>
          )}
        </section>
      </main>
    </div>
  );
}
