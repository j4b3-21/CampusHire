import * as React from "react";

import { cn } from "@/lib/utils";

export function PageHeader({
  title,
  description,
  actions,
  icon,
  className,
  children,
}: {
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  icon?: React.ReactNode;
  className?: string;
  children?: React.ReactNode;
}) {
  return (
    <div className={cn("flex flex-col gap-4 px-4 pb-4 pt-6 sm:px-6 lg:px-8", className)}>
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
        <div className="flex min-w-0 items-start gap-3">
          {icon}
          <div className="min-w-0">
            <h1 className="truncate text-xl font-semibold tracking-[-0.025em]">{title}</h1>
            {description ? (
              <div className="mt-0.5 text-[13px] leading-relaxed text-muted-foreground">{description}</div>
            ) : null}
          </div>
        </div>
        {actions ? <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div> : null}
      </div>
      {children}
    </div>
  );
}

export function PageBody({ className, ...props }: React.ComponentProps<"div">) {
  return <div className={cn("flex-1 px-4 pb-10 sm:px-6 lg:px-8", className)} {...props} />;
}
