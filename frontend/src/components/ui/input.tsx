import * as React from "react";

import { cn } from "@/lib/utils";

export const inputClassName =
  "flex h-9 w-full min-w-0 rounded-md border border-input bg-background px-3 py-1 text-sm text-foreground shadow-xs transition-[border-color,box-shadow] placeholder:text-subtle-foreground hover:border-border-strong focus-visible:border-ring focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/20 disabled:cursor-not-allowed disabled:bg-muted disabled:text-muted-foreground disabled:opacity-80 aria-invalid:border-destructive aria-invalid:ring-destructive/15 file:border-0 file:bg-transparent file:text-sm file:font-medium";

export function Input({ className, type, ...props }: React.ComponentProps<"input">) {
  return <input data-slot="input" type={type} className={cn(inputClassName, className)} {...props} />;
}
