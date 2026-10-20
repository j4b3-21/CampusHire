"use client";

import Link from "next/link";
import { Lock } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ADMIN_NAV, orgPath } from "@/lib/navigation";

export function AdminSection({ section }: { section: string }) {
  const { orgId, isAdmin, loading } = useOrg();
  const item = ADMIN_NAV.find((entry) => entry.section === section);

  if (!loading && !isAdmin) {
    return (
      <EmptyState
        icon={Lock}
        title="Admins only"
        description="Ask your placement officer if you need access to this page."
        action={
          <Button variant="outline" asChild>
            <Link href={orgPath(orgId)}>Back to dashboard</Link>
          </Button>
        }
      />
    );
  }

  if (!item) {
    return <EmptyState title="Page not found" description="This admin page doesn't exist." />;
  }

  return (
    <>
      <PageHeader title={item.label} description={item.description} />
      <PageBody>
        <div className="rounded-xl border border-dashed border-border-strong">
          <EmptyState
            icon={item.icon}
            title={`${item.label} is on its way`}
            description="This page is part of the next release. Everything here will work without leaving CampusHire."
          />
        </div>
      </PageBody>
    </>
  );
}
