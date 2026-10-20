import * as React from "react";
import type { LucideIcon } from "lucide-react";

import { cn } from "@/lib/utils";

export interface EmptyStateProps {
  icon?: LucideIcon;
  title: React.ReactNode;
  description?: React.ReactNode;
  action?: React.ReactNode;
  className?: string;
  tone?: "default" | "destructive";
  size?: "default" | "sm";
}

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
  tone = "default",
  size = "default",
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center text-center",
        size === "sm" ? "gap-2 px-4 py-8" : "gap-3 px-6 py-16",
        className
      )}
    >
      {Icon ? (
        <div
          className={cn(
            "mb-1 grid place-items-center rounded-lg border shadow-xs",
            size === "sm" ? "size-9" : "size-11",
            tone === "destructive"
              ? "border-destructive/20 bg-destructive-soft text-destructive"
              : "border-border bg-background text-muted-foreground"
          )}
        >
          <Icon className={size === "sm" ? "size-4" : "size-5"} strokeWidth={1.75} />
        </div>
      ) : null}
      <div className="flex max-w-sm flex-col gap-1">
        <h3 className={cn("font-semibold tracking-[-0.01em]", size === "sm" ? "text-[13px]" : "text-sm")}>{title}</h3>
        {description ? <p className="text-[13px] leading-relaxed text-muted-foreground">{description}</p> : null}
      </div>
      {action ? <div className="mt-2 flex flex-wrap items-center justify-center gap-2">{action}</div> : null}
    </div>
  );
}
