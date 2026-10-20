"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import { ArrowLeft, ArrowRight, Briefcase, CheckCircle2, GraduationCap, LogOut, MailQuestion } from "lucide-react";

import { DomainsInput } from "@/components/common/domains-input";
import { BrandWordmark } from "@/components/common/brand";
import { LoadingScreen } from "@/components/common/loading-screen";
import { ThemeToggle } from "@/components/common/theme-toggle";
import { UserMenu } from "@/components/layout/user-menu";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { useAuth } from "@/hooks/use-auth";
import {
  ApiError,
  errorMessage,
  useCampusOnboarding,
  useClaimStudentRecord,
  useCreateOrganization,
  useOrganizations,
} from "@/lib/api";
import type { OnboardingOrganization } from "@/types/campushire";

const schema = z.object({
  name: z.string().trim().min(2, "Enter at least 2 characters").max(200, "Use at most 200 characters"),
});

type FormValues = z.infer<typeof schema>;

const INSTALLS = [
  "Students, departments and batches",
  "Companies, contacts and follow-ups",
  "Jobs, drives, applications, interviews and offers",
  "Roles for placement staff, coordinators, students and recruiters",
];

function CreateInstituteForm() {
  const router = useRouter();
  const create = useCreateOrganization();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: "" } });
  const nameError = form.formState.errors.name?.message;
  const [domains, setDomains] = React.useState<string[]>([]);

  const onSubmit = form.handleSubmit(async ({ name }) => {
    try {
      const result = await create.mutateAsync({ name, allowedEmailDomains: domains });
      toast.success(`${result.organization.name} is ready`, {
        description: result.setupCompleted
          ? "CampusHire modules are installed."
          : "Placement modules will appear here once they're installed for your institute.",
      });
      router.replace(`/app/${result.organization.id}`);
    } catch (error) {
      if (error instanceof ApiError && error.isValidation) {
        form.setError("name", { message: error.fieldErrors("").name ?? error.friendlyMessage });
      }
    }
  });

  return (
    <section aria-labelledby="create-heading" className="rounded-xl border border-border bg-card p-6 shadow-xs sm:p-7">
      <h2 id="create-heading" className="text-base font-semibold tracking-[-0.01em]">
        Set up your institute
      </h2>
      <p className="mt-1 text-[13px] leading-relaxed text-muted-foreground">
        For placement officers. You become the owner and can invite coordinators and recruiters afterwards.
      </p>
      <form onSubmit={onSubmit} className="mt-6 flex flex-col gap-2" noValidate>
        <Label htmlFor="institute-name">Institute name</Label>
        <Input
          id="institute-name"
          placeholder="Indian Institute of Information Technology, Tiruchirappalli"
          autoComplete="organization"
          autoFocus
          aria-invalid={Boolean(nameError) || undefined}
          aria-describedby={nameError ? "institute-name-error" : undefined}
          {...form.register("name")}
        />
        {nameError ? (
          <p id="institute-name-error" className="text-xs text-destructive">
            {nameError}
          </p>
        ) : null}
        <Label htmlFor="institute-domains" className="mt-3">
          Student email domains <span className="font-normal text-muted-foreground">(optional)</span>
        </Label>
        <DomainsInput id="institute-domains" value={domains} onChange={setDomains} describedBy="institute-domains-hint" />
        <p id="institute-domains-hint" className="text-xs text-muted-foreground">
          Students signing in with these addresses are matched to their records. You can change this later in Settings.
        </p>
        <Button type="submit" className="mt-3 self-start" disabled={create.isPending}>
          {create.isPending ? <Spinner className="text-primary-foreground" /> : null}
          {create.isPending ? "Creating institute…" : "Create institute"}
        </Button>
      </form>
      <div className="mt-7 border-t border-border pt-5">
        <p className="text-xs font-medium text-muted-foreground">Comes with</p>
        <ul className="mt-2.5 grid gap-1.5 text-[13px]">
          {INSTALLS.map((item) => (
            <li key={item} className="flex items-start gap-2">
              <span className="mt-[7px] size-1 shrink-0 rounded-full bg-primary" aria-hidden />
              {item}
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

function JoinGuidance() {
  const { profile, logout } = useAuth();
  return (
    <section aria-labelledby="join-heading" className="rounded-xl border border-dashed border-border-strong p-6 sm:p-7">
      <h2 id="join-heading" className="text-base font-semibold tracking-[-0.01em]">
        Joining as a student or recruiter?
      </h2>
      <p className="mt-1 text-[13px] leading-relaxed text-muted-foreground">
        You don&apos;t need to create anything. Your placement cell gives you access.
      </p>
      <div className="mt-6 grid gap-5">
        <div className="flex gap-3">
          <span className="grid size-8 shrink-0 place-items-center rounded-md bg-primary-soft text-primary-soft-foreground">
            <GraduationCap className="size-4" />
          </span>
          <div>
            <p className="text-[13px] font-medium">Students</p>
            <p className="mt-0.5 text-[13px] leading-relaxed text-muted-foreground">
              Sign in with your college Google account, such as name@iiitt.ac.in. Once the placement cell has added
              you, your profile is linked automatically.
            </p>
          </div>
        </div>
        <div className="flex gap-3">
          <span className="grid size-8 shrink-0 place-items-center rounded-md bg-primary-soft text-primary-soft-foreground">
            <Briefcase className="size-4" />
          </span>
          <div>
            <p className="text-[13px] font-medium">Recruiters and coordinators</p>
            <p className="mt-0.5 text-[13px] leading-relaxed text-muted-foreground">
              Open the invitation link from your placement cell&apos;s email and sign in with the address it was sent
              to.
            </p>
          </div>
        </div>
      </div>
      <div className="mt-7 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-5">
        <p className="min-w-0 truncate text-xs text-muted-foreground">
          Signed in as <span className="font-medium text-foreground">{profile?.email ?? profile?.username}</span>
        </p>
        <Button variant="outline" size="sm" onClick={() => void logout()}>
          <LogOut /> Use a different account
        </Button>
      </div>
    </section>
  );
}

function StudentInstituteCard({ institute, domain }: { institute: OnboardingOrganization; domain: string | null }) {
  const router = useRouter();
  const claim = useClaimStudentRecord();
  const [error, setError] = React.useState<string | null>(null);

  const join = async () => {
    setError(null);
    try {
      const result = await claim.mutateAsync(institute.organizationId);
      toast.success(`Welcome to ${institute.name}`, {
        description: result.alreadyLinked ? "Your student profile was already linked." : "Your student profile is linked to this account.",
      });
      router.replace(`/app/${result.organizationId}`);
    } catch (cause) {
      if (cause instanceof ApiError && cause.code === "already_member") {
        router.replace(`/app/${institute.organizationId}`);
        return;
      }
      setError(errorMessage(cause));
    }
  };

  if (institute.member || institute.linked) {
    return (
      <div className="flex flex-col gap-4 rounded-xl border border-border bg-card p-5 sm:flex-row sm:items-center">
        <span className="grid size-10 shrink-0 place-items-center rounded-lg bg-success-soft text-success-soft-foreground">
          <CheckCircle2 className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold">You&apos;re already part of {institute.name}</p>
          <p className="text-[13px] text-muted-foreground">Open it to see drives and your applications.</p>
        </div>
        <Button asChild>
          <Link href={`/app/${institute.organizationId}`}>
            Open {institute.name} <ArrowRight />
          </Link>
        </Button>
      </div>
    );
  }

  if (!institute.studentRecordFound) {
    return (
      <div role="status" className="flex flex-col gap-3 rounded-xl border border-warning/30 bg-warning-soft p-5 text-warning-soft-foreground sm:flex-row sm:items-start">
        <MailQuestion className="size-5 shrink-0" />
        <div className="min-w-0 text-[13px]">
          <p className="text-sm font-semibold">{institute.name} hasn&apos;t added you yet</p>
          <p className="mt-1 leading-relaxed opacity-90">
            Your email domain{domain ? ` (${domain})` : ""} belongs to {institute.name}, but there&apos;s no student record with your email address. Contact
            your placement cell and ask them to add you with this email; then sign in again.
          </p>
        </div>
      </div>
    );
  }

  return (
    <section aria-labelledby={`join-${institute.organizationId}`} className="flex flex-col gap-5 rounded-xl border border-primary/25 bg-card p-6 shadow-xs sm:p-7">
      <div className="flex items-start gap-4">
        <span className="grid size-11 shrink-0 place-items-center rounded-xl bg-primary text-lg font-semibold text-primary-foreground">
          {institute.name.charAt(0).toUpperCase()}
        </span>
        <div className="min-w-0">
          <h2 id={`join-${institute.organizationId}`} className="text-lg font-semibold tracking-[-0.02em]">
            Join {institute.name} as a student
          </h2>
          <p className="mt-1 text-[13px] leading-relaxed text-muted-foreground">
            Your placement cell has a student record with your email address. Joining links it to this account so you can see drives you&apos;re eligible
            for, apply, and track offers.
          </p>
        </div>
      </div>
      <ul className="grid gap-1.5 text-[13px] sm:grid-cols-3">
        {["See eligible drives", "Apply in one click", "Track every stage"].map((item) => (
          <li key={item} className="flex items-center gap-2">
            <CheckCircle2 className="size-3.5 text-primary" /> {item}
          </li>
        ))}
      </ul>
      {error ? (
        <p role="alert" className="rounded-md bg-destructive-soft px-3 py-2 text-[13px] text-destructive-soft-foreground">
          {error}
        </p>
      ) : null}
      <div className="flex flex-wrap items-center gap-3">
        <Button size="lg" onClick={() => void join()} disabled={claim.isPending}>
          {claim.isPending ? <Spinner className="text-primary-foreground" /> : <GraduationCap />}
          Join as a student
        </Button>
        <span className="text-xs text-muted-foreground">Your academic details stay managed by the placement cell.</span>
      </div>
    </section>
  );
}

export function Onboarding() {
  const { profile } = useAuth();
  const orgs = useOrganizations();
  const searchParams = useSearchParams();
  const hasOrgs = (orgs.data?.length ?? 0) > 0;
  const creatingAnother = hasOrgs && searchParams.get("create") === "1";
  const firstName = profile?.name?.split(" ")[0];
  const onboarding = useCampusOnboarding({ enabled: !creatingAnother });
  const institutes = onboarding.data?.organizations ?? [];
  const [showCreate, setShowCreate] = React.useState(false);
  const studentFlow = !creatingAnother && institutes.length > 0;

  if (!creatingAnother && onboarding.isLoading) return <LoadingScreen label="Looking up your institute…" />;
  if (studentFlow) {
    return (
      <div className="flex min-h-dvh flex-col bg-surface">
        <header className="flex h-14 items-center justify-between border-b border-border bg-background px-4 sm:px-6">
          <BrandWordmark />
          <div className="flex items-center gap-1.5">
            <ThemeToggle />
            <UserMenu />
          </div>
        </header>
        <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col px-4 py-12 sm:px-6 sm:py-16">
          <h1 className="text-[1.75rem] font-semibold leading-tight tracking-[-0.03em] text-balance">Welcome{firstName ? `, ${firstName}` : ""}</h1>
          <p className="mt-2 text-[15px] leading-relaxed text-muted-foreground">
            We found {institutes.length === 1 ? "your institute" : "institutes"} for {onboarding.data?.email ?? profile?.email}.
          </p>
          <div className="mt-8 flex flex-col gap-4">
            {institutes.map((institute) => (
              <StudentInstituteCard key={institute.organizationId} institute={institute} domain={onboarding.data?.domain ?? null} />
            ))}
          </div>
          <div className="mt-10 border-t border-border pt-6">
            {showCreate ? (
              <CreateInstituteForm />
            ) : (
              <p className="text-[13px] text-muted-foreground">
                Setting up placements for a new institute instead?{" "}
                <button type="button" className="focus-ring rounded-sm font-medium text-primary hover:underline" onClick={() => setShowCreate(true)}>
                  Create an institute
                </button>
              </p>
            )}
          </div>
        </main>
      </div>
    );
  }

  return (
    <div className="flex min-h-dvh flex-col bg-surface">
      <header className="flex h-14 items-center justify-between border-b border-border bg-background px-4 sm:px-6">
        <BrandWordmark />
        <div className="flex items-center gap-1.5">
          <ThemeToggle />
          <UserMenu />
        </div>
      </header>
      <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col px-4 py-12 sm:px-6 sm:py-16">
        {hasOrgs ? (
          <Button variant="subtle" size="sm" asChild className="mb-6 self-start">
            <Link href="/app">
              <ArrowLeft /> Back to my institutes
            </Link>
          </Button>
        ) : null}
        <h1 className="text-[1.75rem] font-semibold leading-tight tracking-[-0.03em] text-balance">
          {creatingAnother ? "Create another institute" : `Welcome${firstName ? `, ${firstName}` : ""}`}
        </h1>
        <p className="mt-2 max-w-xl text-[15px] leading-relaxed text-muted-foreground">
          {creatingAnother
            ? "Each institute has its own students, companies, drives and roles."
            : "You're not part of an institute yet. Set one up, or find out how to get access to yours."}
        </p>
        <div className={creatingAnother ? "mt-10 max-w-xl" : "mt-10 grid items-start gap-5 lg:grid-cols-[1.15fr_1fr]"}>
          <CreateInstituteForm />
          {!creatingAnother ? <JoinGuidance /> : null}
        </div>
      </main>
    </div>
  );
}
