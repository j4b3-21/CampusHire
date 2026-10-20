import * as React from "react";
import { Slot } from "@radix-ui/react-slot";
import { ChevronRight } from "lucide-react";

import { cn } from "@/lib/utils";

export function Breadcrumb(props: React.ComponentProps<"nav">) {
  return <nav aria-label="Breadcrumb" {...props} />;
}

export function BreadcrumbList({ className, ...props }: React.ComponentProps<"ol">) {
  return (
    <ol
      className={cn("flex min-w-0 items-center gap-1 text-[13px] text-muted-foreground", className)}
      {...props}
    />
  );
}

export function BreadcrumbItem({ className, ...props }: React.ComponentProps<"li">) {
  return <li className={cn("inline-flex min-w-0 items-center gap-1", className)} {...props} />;
}

export function BreadcrumbLink({
  asChild,
  className,
  ...props
}: React.ComponentProps<"a"> & { asChild?: boolean }) {
  const Comp = asChild ? Slot : "a";
  return (
    <Comp
      className={cn("focus-ring truncate rounded-sm transition-colors hover:text-foreground", className)}
      {...props}
    />
  );
}

export function BreadcrumbPage({ className, ...props }: React.ComponentProps<"span">) {
  return (
    <span
      role="link"
      aria-disabled="true"
      aria-current="page"
      className={cn("truncate font-medium text-foreground", className)}
      {...props}
    />
  );
}

export function BreadcrumbSeparator({ className, ...props }: React.ComponentProps<"li">) {
  return (
    <li role="presentation" aria-hidden className={cn("text-subtle-foreground [&>svg]:size-3.5", className)} {...props}>
      <ChevronRight />
    </li>
  );
}
