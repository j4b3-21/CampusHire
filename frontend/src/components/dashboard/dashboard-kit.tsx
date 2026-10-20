import * as React from "react";
import Link from "next/link";
import { ArrowRight, type LucideIcon } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { DRIVE_STATUS_TONES, driveStatusLabel } from "@/lib/campushire";
import { TONE_CLASSES } from "@/lib/metadata/tones";
import { cn } from "@/lib/utils";
import type { DriveStatus } from "@/types/campushire";

export interface Kpi {
  label: string;
  value: React.ReactNode;
  hint?: React.ReactNode;
  icon?: LucideIcon;
}

/** Hairline-separated KPI row (Linear/Vercel style). */
export function KpiGrid({ items, loading, className }: { items: Kpi[]; loading?: boolean; className?: string }) {
  return (
    <section aria-label="Key numbers" className={cn("flex flex-wrap overflow-hidden rounded-xl border border-border bg-card", className)}>
      {items.map((kpi) => (
        <div key={kpi.label} className="-ml-px -mt-px flex min-w-0 flex-[1_1_11rem] flex-col gap-2 border-l border-t border-border p-5">
          <p className="flex items-center gap-1.5 text-[13px] font-medium text-muted-foreground">
            {kpi.icon ? <kpi.icon className="size-3.5" aria-hidden /> : null}
            {kpi.label}
          </p>
          {loading ? (
            <Skeleton className="h-7 w-24" />
          ) : (
            <p className="text-2xl font-semibold tracking-[-0.03em] tabular">{kpi.value}</p>
          )}
          {kpi.hint ? <p className="text-xs text-subtle-foreground">{kpi.hint}</p> : null}
        </div>
      ))}
    </section>
  );
}

/** Card with a header row and optional "view all" link. */
export function Panel({
  title,
  description,
  href,
  hrefLabel = "View all",
  actions,
  className,
  bodyClassName,
  children,
}: {
  title: React.ReactNode;
  description?: React.ReactNode;
  href?: string;
  hrefLabel?: string;
  actions?: React.ReactNode;
  className?: string;
  bodyClassName?: string;
  children: React.ReactNode;
}) {
  return (
    <section className={cn("flex min-w-0 flex-col rounded-lg border border-border bg-card", className)}>
      <header className="flex items-start gap-3 border-b border-border px-4 py-3">
        <div className="min-w-0 flex-1">
          <h2 className="text-[13px] font-semibold">{title}</h2>
          {description ? <p className="mt-0.5 text-xs text-muted-foreground">{description}</p> : null}
        </div>
        {actions}
        {href ? (
          <Link
            href={href}
            className="focus-ring inline-flex shrink-0 items-center gap-1 rounded-sm text-xs font-medium text-muted-foreground hover:text-foreground"
          >
            {hrefLabel} <ArrowRight className="size-3" />
          </Link>
        ) : null}
      </header>
      <div className={cn("min-w-0 flex-1", bodyClassName ?? "p-4")}>{children}</div>
    </section>
  );
}

export function DriveStatusBadge({ status, className }: { status: string | null | undefined; className?: string }) {
  const tone = DRIVE_STATUS_TONES[status as DriveStatus] ?? "neutral";
  return (
    <Badge variant="neutral" className={cn(TONE_CLASSES[tone], className)}>
      {driveStatusLabel(status)}
    </Badge>
  );
}

/** Small numeric stat with a label, used in strips under the KPI row. */
export function MiniStat({ label, value, href, tooltip }: { label: string; value: React.ReactNode; href?: string; tooltip?: string }) {
  const content = (
    <>
      <span className="text-[13px] text-muted-foreground">{label}</span>
      <span className="text-[15px] font-semibold tabular">{value}</span>
    </>
  );
  const className = "flex items-center justify-between gap-3 rounded-lg border border-border bg-card px-4 py-3";
  const node = href ? (
    <Link href={href} className={cn("focus-ring transition-colors hover:border-border-strong hover:bg-surface", className)}>
      {content}
    </Link>
  ) : (
    <div className={className}>{content}</div>
  );
  return tooltip ? <SimpleTooltip label={tooltip}>{node}</SimpleTooltip> : node;
}

export function greeting(now = new Date()) {
  const hour = now.getHours();
  if (hour < 12) return "Good morning";
  if (hour < 17) return "Good afternoon";
  return "Good evening";
}
