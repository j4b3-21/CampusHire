"use client";

import * as React from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { AlertTriangle, Mail, RotateCw } from "lucide-react";

import { BrandWordmark } from "@/components/common/brand";
import { ThemeToggle } from "@/components/common/theme-toggle";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useAuth } from "@/hooks/use-auth";
import { safeNextPath } from "@/lib/auth/config";
import { resetPasswordUrl } from "@/lib/auth/keycloak";
import { cn } from "@/lib/utils";

function GoogleIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden className={className}>
      <path fill="#4285F4" d="M23.5 12.27c0-.79-.07-1.54-.2-2.27H12v4.3h6.46a5.52 5.52 0 0 1-2.4 3.62v3h3.88c2.27-2.09 3.56-5.17 3.56-8.65z" />
      <path fill="#34A853" d="M12 24c3.24 0 5.96-1.07 7.94-2.9l-3.88-3c-1.07.72-2.45 1.15-4.06 1.15-3.12 0-5.77-2.11-6.71-4.95H1.28v3.1A12 12 0 0 0 12 24z" />
      <path fill="#FBBC05" d="M5.29 14.3a7.2 7.2 0 0 1 0-4.6V6.6H1.28a12 12 0 0 0 0 10.8l4.01-3.1z" />
      <path fill="#EA4335" d="M12 4.75c1.76 0 3.34.6 4.59 1.8l3.44-3.44A11.94 11.94 0 0 0 12 0 12 12 0 0 0 1.28 6.6l4.01 3.1C6.23 6.86 8.88 4.75 12 4.75z" />
    </svg>
  );
}

const PIPELINE = [
  { stage: "Applied", count: 412 },
  { stage: "Shortlisted", count: 138 },
  { stage: "Interview", count: 64 },
  { stage: "Offered", count: 29 },
] as const;

/** A drive as the placement cell sees it: who is eligible and how far applicants have moved. */
function DrivePreview() {
  const max = PIPELINE[0].count;
  return (
    <figure
      aria-label="Example placement drive"
      className="w-full max-w-md rounded-xl border border-border bg-background p-5 shadow-md"
    >
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm font-semibold tracking-[-0.01em]">Acme Robotics, Software Engineer</p>
          <p className="mt-0.5 text-[13px] text-muted-foreground">On campus, 14 Nov. Registration closes Friday.</p>
        </div>
        <span className="inline-flex items-center gap-1.5 rounded-md bg-success-soft px-1.5 py-px text-xs font-medium leading-5 text-success-soft-foreground">
          <span className="size-1.5 rounded-full bg-current" aria-hidden />
          Published
        </span>
      </div>

      <div className="mt-4 flex flex-wrap gap-1.5 text-xs">
        {["CGPA 7.0 or above", "No active backlogs", "CSE, ECE", "2027 batch"].map((rule) => (
          <span key={rule} className="rounded-md border border-border px-1.5 py-px leading-5 text-muted-foreground">
            {rule}
          </span>
        ))}
      </div>

      <dl className="mt-5 space-y-2.5">
        {PIPELINE.map(({ stage, count }, index) => (
          <div key={stage} className="grid grid-cols-[6.5rem_1fr_2.5rem] items-center gap-3 text-[13px]">
            <dt className="text-muted-foreground">{stage}</dt>
            <div className="h-2 overflow-hidden rounded-full bg-muted" aria-hidden>
              <div
                className={cn("h-full rounded-full", index === PIPELINE.length - 1 ? "bg-primary" : "bg-primary/35")}
                style={{ width: `${Math.max(6, (count / max) * 100)}%` }}
              />
            </div>
            <dd className="text-right font-medium tabular">{count}</dd>
          </div>
        ))}
      </dl>

      <figcaption className="mt-5 flex items-center justify-between border-t border-border pt-3 text-xs text-muted-foreground">
        <span>318 of 540 students eligible</span>
        <span className="tabular">Median CTC ₹14.5 LPA</span>
      </figcaption>
    </figure>
  );
}

export function LoginScreen() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const next = safeNextPath(searchParams.get("next"));
  const { status, error, login, register } = useAuth();
  const [pending, setPending] = React.useState<"google" | "email" | "register" | null>(null);

  React.useEffect(() => {
    if (status === "authenticated") router.replace(next);
  }, [status, next, router]);

  const start = async (kind: "google" | "email" | "register") => {
    setPending(kind);
    try {
      if (kind === "register") await register({ next });
      else await login({ next, idpHint: kind === "google" ? "google" : undefined });
    } catch {
      setPending(null);
    }
  };

  const busy = status !== "unauthenticated" || pending !== null;

  return (
    <div className="grid min-h-dvh lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]">
      <aside className="relative hidden flex-col justify-between overflow-hidden border-r border-border bg-surface px-12 py-10 lg:flex xl:px-16">
        <BrandWordmark />
        <div className="max-w-lg">
          <h1 className="text-[2.5rem] font-semibold leading-[1.08] tracking-[-0.035em] text-balance">
            Placement season, run from one place.
          </h1>
          <p className="mt-4 max-w-md text-[15px] leading-relaxed text-muted-foreground">
            Students, companies, drives and offers for your Training & Placement cell. Eligibility is checked for you,
            students apply in one click, and recruiters see only their own applicants.
          </p>
          <div className="mt-10">
            <DrivePreview />
          </div>
        </div>
        <p className="text-xs text-subtle-foreground">For placement officers, coordinators, students and recruiters.</p>
      </aside>

      <main className="relative flex flex-col px-6 py-8 sm:px-10">
        <div className="flex items-center justify-between lg:justify-end">
          <BrandWordmark className="lg:hidden" />
          <ThemeToggle />
        </div>

        <div className="mx-auto flex w-full max-w-[22rem] flex-1 flex-col justify-center py-12">
          <h2 className="text-2xl font-semibold tracking-[-0.025em]">Sign in to CampusHire</h2>
          <p className="mt-2 text-[14px] leading-relaxed text-muted-foreground">
            Students: use your college Google account. Placement staff and recruiters can also sign in with email.
          </p>

          {error ? (
            <div
              role="alert"
              className="mt-6 flex gap-3 rounded-lg border border-warning/30 bg-warning-soft p-3 text-[13px] text-warning-soft-foreground"
            >
              <AlertTriangle className="mt-0.5 size-4 shrink-0" />
              <div className="flex-1">
                <p className="font-medium">{error}</p>
                <p className="mt-0.5 opacity-90">Wait a moment, then try again.</p>
              </div>
              <Button variant="ghost" size="icon-xs" aria-label="Retry" onClick={() => window.location.reload()}>
                <RotateCw />
              </Button>
            </div>
          ) : null}

          <div className="mt-8 flex flex-col gap-2.5">
            <Button variant="outline" size="xl" disabled={busy} onClick={() => start("google")} className="w-full">
              {pending === "google" ? <Spinner /> : <GoogleIcon className="size-[18px]" />}
              Continue with Google
            </Button>
            <Button size="xl" disabled={busy} onClick={() => start("email")} className="w-full">
              {pending === "email" ? <Spinner className="text-primary-foreground" /> : <Mail />}
              Sign in with email
            </Button>
          </div>

          <div className="mt-6 flex items-center justify-between text-[13px]">
            <a
              href={resetPasswordUrl()}
              className="focus-ring rounded-sm text-muted-foreground transition-colors hover:text-foreground"
            >
              Forgot password?
            </a>
            <button
              type="button"
              disabled={busy}
              onClick={() => start("register")}
              className="focus-ring rounded-sm font-medium text-primary transition-colors hover:text-primary-hover disabled:opacity-50"
            >
              {pending === "register" ? "Opening…" : "Create an account"}
            </button>
          </div>

          {status === "loading" ? (
            <p className="mt-8 flex items-center gap-2 text-xs text-muted-foreground">
              <Spinner className="size-3" /> Checking for an existing session…
            </p>
          ) : null}
        </div>

        <p className="mx-auto max-w-[22rem] text-center text-xs leading-relaxed text-subtle-foreground">
          Got an invitation from your placement cell? Open the link in the email and sign in with the same address.
        </p>
      </main>
    </div>
  );
}
