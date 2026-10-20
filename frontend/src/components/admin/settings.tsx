"use client";

import * as React from "react";
import { toast } from "sonner";
import { Save } from "lucide-react";

import { AdminGate, FieldMessage } from "@/components/admin/admin-gate";
import { DomainsInput } from "@/components/common/domains-input";
import { useOrg } from "@/components/app/org-context";
import { ErrorState } from "@/components/common/error-state";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { ApiError, useOrganizationDetail, useUpdateOrganization } from "@/lib/api";
import { violationMap } from "@/lib/api/violations";
import type { JsonObject, JsonValue } from "@/types/api";
import type { Organization } from "@/types/org";

const RUPEES_PER_LAKH = 100_000;

function asObject(value: JsonValue | undefined): JsonObject {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

function SettingsForm({ organization }: { organization: Organization & { etag: string } }) {
  const { isAdmin } = useOrg();
  const update = useUpdateOrganization(organization.id);
  const settings = asObject(organization.settings ?? undefined);
  const campushire = asObject(settings.campushire);
  const policy = asObject(campushire.policy);
  const domainsValue = campushire.allowedEmailDomains;

  const [name, setName] = React.useState(organization.name);
  const [domains, setDomains] = React.useState<string[]>(Array.isArray(domainsValue) ? domainsValue.filter((d): d is string => typeof d === "string") : []);
  const [maxOffers, setMaxOffers] = React.useState(typeof policy.maxOffersPerStudent === "number" ? String(policy.maxOffersPerStudent) : "1");
  const [dreamLpa, setDreamLpa] = React.useState(
    typeof policy.dreamCtcThreshold === "number" ? String(policy.dreamCtcThreshold / RUPEES_PER_LAKH) : "12"
  );
  const [allowUpgrade, setAllowUpgrade] = React.useState(typeof policy.allowDreamUpgrade === "boolean" ? policy.allowDreamUpgrade : true);
  const [errors, setErrors] = React.useState<Map<string, string[]>>(new Map());
  const err = (path: string) => errors.get(path)?.join(" ");

  const save = async (event: React.FormEvent) => {
    event.preventDefault();
    const local = new Map<string, string[]>();
    const offers = Number.parseInt(maxOffers, 10);
    const lpa = Number(dreamLpa);
    if (!name.trim()) local.set("name", ["Enter the institute name"]);
    if (!Number.isInteger(offers) || offers < 1 || offers > 20) local.set("maxOffers", ["Between 1 and 20"]);
    if (!Number.isFinite(lpa) || lpa < 0) local.set("dream", ["Enter an amount in lakhs per annum"]);
    setErrors(local);
    if (local.size) return;

    const nextSettings: JsonObject = {
      ...settings,
      campushire: {
        ...campushire,
        allowedEmailDomains: domains,
        policy: {
          ...policy,
          maxOffersPerStudent: offers,
          dreamCtcThreshold: Math.round(lpa * RUPEES_PER_LAKH),
          allowDreamUpgrade: allowUpgrade,
        },
      },
    };
    try {
      await update.mutateAsync({ ifMatch: organization.etag, name: name.trim(), settings: nextSettings });
      toast.success("Settings saved");
    } catch (error) {
      if (error instanceof ApiError && error.isValidation) setErrors(violationMap(error));
      else if (error instanceof ApiError && error.isVersionConflict) {
        toast.error("Someone else changed the settings", { description: "Reload the page to get the latest values." });
      }
    }
  };

  const lpaNumber = Number(dreamLpa);

  return (
    <form onSubmit={save} className="flex max-w-3xl flex-col gap-6" noValidate>
      <Card>
        <CardHeader>
          <CardTitle>Institute</CardTitle>
          <CardDescription>Shown to students, coordinators and recruiters.</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-1.5">
          <Label htmlFor="org-name">Name</Label>
          <Input id="org-name" value={name} maxLength={200} aria-invalid={Boolean(err("name")) || undefined} onChange={(e) => setName(e.target.value)} />
          <FieldMessage error={err("name")} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Student sign-in</CardTitle>
          <CardDescription>Students who sign in with these email domains are matched to their student record.</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-1.5">
          <Label htmlFor="org-domains">Allowed email domains</Label>
          <DomainsInput id="org-domains" value={domains} onChange={setDomains} describedBy="org-domains-hint" />
          <FieldMessage id="org-domains-hint" hint="Press Enter after each domain, e.g. iiitt.ac.in." />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Placement policy</CardTitle>
          <CardDescription>Used by the eligibility check when students apply to drives.</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-5 sm:grid-cols-2">
          <div className="grid gap-1.5">
            <Label htmlFor="policy-offers">Offers per student</Label>
            <Input
              id="policy-offers"
              inputMode="numeric"
              value={maxOffers}
              aria-invalid={Boolean(err("maxOffers")) || undefined}
              onChange={(e) => setMaxOffers(e.target.value)}
            />
            <FieldMessage error={err("maxOffers")} hint="After this many active offers a student can't apply to more drives." />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="policy-dream">Dream offer from (LPA)</Label>
            <div className="relative">
              <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">₹</span>
              <Input
                id="policy-dream"
                inputMode="decimal"
                value={dreamLpa}
                className="pl-7 pr-12"
                aria-invalid={Boolean(err("dream")) || undefined}
                aria-describedby="policy-dream-hint"
                onChange={(e) => setDreamLpa(e.target.value)}
              />
              <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">lakh</span>
            </div>
            <FieldMessage
              id="policy-dream-hint"
              error={err("dream")}
              hint={Number.isFinite(lpaNumber) ? `= ₹${Math.round(lpaNumber * RUPEES_PER_LAKH).toLocaleString("en-IN")} per year` : undefined}
            />
          </div>
          <label htmlFor="policy-upgrade" className="flex items-start gap-3 sm:col-span-2">
            <Switch id="policy-upgrade" checked={allowUpgrade} onCheckedChange={setAllowUpgrade} className="mt-0.5" />
            <span>
              <span className="block text-[13px] font-medium">Allow dream upgrades</span>
              <span className="block text-xs text-muted-foreground">
                Students at the offer limit may still apply to dream drives that pay more than their best offer.
              </span>
            </span>
          </label>
        </CardContent>
      </Card>

      {[...errors.entries()].filter(([p]) => !["name", "maxOffers", "dream"].includes(p)).map(([path, messages]) => (
        <FieldMessage key={path} error={`${path}: ${messages.join(" ")}`} />
      ))}
      <div className="flex justify-end">
        <Button type="submit" disabled={update.isPending || !isAdmin}>
          {update.isPending ? <Spinner className="text-primary-foreground" /> : <Save />} Save settings
        </Button>
      </div>
    </form>
  );
}

export function SettingsPage() {
  const { orgId } = useOrg();
  const organization = useOrganizationDetail(orgId);
  return (
    <AdminGate>
      <PageHeader title="Settings" description="Institute name, allowed student email domains and placement policy." />
      <PageBody>
        {organization.isLoading ? (
          <div className="flex max-w-3xl flex-col gap-4">
            <Skeleton className="h-32 rounded-lg" />
            <Skeleton className="h-32 rounded-lg" />
            <Skeleton className="h-48 rounded-lg" />
          </div>
        ) : organization.isError || !organization.data ? (
          <ErrorState error={organization.error} onRetry={() => void organization.refetch()} />
        ) : (
          <SettingsForm key={organization.data.version} organization={organization.data} />
        )}
      </PageBody>
    </AdminGate>
  );
}
