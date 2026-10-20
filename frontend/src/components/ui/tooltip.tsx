"use client";

import * as React from "react";
import * as TooltipPrimitive from "@radix-ui/react-tooltip";

import { cn } from "@/lib/utils";

export const TooltipProvider = TooltipPrimitive.Provider;
export const Tooltip = TooltipPrimitive.Root;
export const TooltipTrigger = TooltipPrimitive.Trigger;

export function TooltipContent({
  className,
  sideOffset = 6,
  ...props
}: React.ComponentProps<typeof TooltipPrimitive.Content>) {
  return (
    <TooltipPrimitive.Portal>
      <TooltipPrimitive.Content
        sideOffset={sideOffset}
        className={cn(
          "z-50 flex items-center gap-2 rounded-md bg-foreground px-2 py-1 text-xs font-medium text-background shadow-md data-[state=delayed-open]:animate-fade-in data-[state=closed]:animate-fade-out",
          className
        )}
        {...props}
      />
    </TooltipPrimitive.Portal>
  );
}

/** Convenience wrapper: `<SimpleTooltip label="Search">{button}</SimpleTooltip>`. */
export function SimpleTooltip({
  label,
  children,
  side,
  shortcut,
  disabled,
}: {
  label: React.ReactNode;
  children: React.ReactNode;
  side?: "top" | "right" | "bottom" | "left";
  shortcut?: string;
  disabled?: boolean;
}) {
  if (disabled) return <>{children}</>;
  return (
    <Tooltip>
      <TooltipTrigger asChild>{children}</TooltipTrigger>
      <TooltipContent side={side}>
        {label}
        {shortcut ? <span className="text-background/60">{shortcut}</span> : null}
      </TooltipContent>
    </Tooltip>
  );
}
