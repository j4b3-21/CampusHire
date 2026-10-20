import * as React from "react";
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

export const badgeVariants = cva(
  "inline-flex max-w-full shrink-0 items-center gap-1 truncate whitespace-nowrap rounded-md border px-1.5 py-px text-xs font-medium leading-5 [&_svg]:size-3",
  {
    variants: {
      variant: {
        default: "border-transparent bg-primary-soft text-primary-soft-foreground",
        neutral: "border-border bg-muted text-foreground/80",
        outline: "border-border bg-transparent text-muted-foreground",
        success: "border-transparent bg-success-soft text-success-soft-foreground",
        warning: "border-transparent bg-warning-soft text-warning-soft-foreground",
        destructive: "border-transparent bg-destructive-soft text-destructive-soft-foreground",
        solid: "border-transparent bg-primary text-primary-foreground",
      },
    },
    defaultVariants: { variant: "default" },
  }
);

export interface BadgeProps extends React.ComponentProps<"span">, VariantProps<typeof badgeVariants> {
  asChild?: boolean;
}

export function Badge({ className, variant, asChild = false, ...props }: BadgeProps) {
  const Comp = asChild ? Slot : "span";
  return <Comp data-slot="badge" className={cn(badgeVariants({ variant }), className)} {...props} />;
}

/** A small colored dot used to differentiate select options. */
export function Dot({ className, ...props }: React.ComponentProps<"span">) {
  return <span aria-hidden className={cn("size-1.5 shrink-0 rounded-full bg-current", className)} {...props} />;
}
