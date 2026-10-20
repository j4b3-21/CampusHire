import * as React from "react";

import { cn } from "@/lib/utils";

export function Kbd({ className, ...props }: React.ComponentProps<"kbd">) {
  return (
    <kbd
      className={cn(
        "pointer-events-none inline-flex h-5 min-w-5 select-none items-center justify-center gap-0.5 rounded-[4px] border border-border bg-surface px-1 font-sans text-[11px] font-medium text-muted-foreground",
        className
      )}
      {...props}
    />
  );
}
