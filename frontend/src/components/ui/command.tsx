"use client";

import * as React from "react";
import { Command as CommandPrimitive } from "cmdk";
import { Search } from "lucide-react";

import { cn } from "@/lib/utils";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";

export function Command({ className, ...props }: React.ComponentProps<typeof CommandPrimitive>) {
  return (
    <CommandPrimitive
      data-slot="command"
      className={cn("flex size-full flex-col overflow-hidden rounded-[inherit] bg-popover text-popover-foreground", className)}
      {...props}
    />
  );
}

export function CommandDialog({
  title = "Command palette",
  description = "Search for a page or an action",
  children,
  className,
  commandProps,
  ...props
}: React.ComponentProps<typeof Dialog> & {
  title?: string;
  description?: string;
  className?: string;
  commandProps?: React.ComponentProps<typeof CommandPrimitive>;
}) {
  return (
    <Dialog {...props}>
      <DialogContent
        showClose={false}
        className={cn("top-[18%] max-w-xl translate-y-0 gap-0 overflow-hidden p-0 sm:top-[16%]", className)}
      >
        <DialogTitle className="sr-only">{title}</DialogTitle>
        <DialogDescription className="sr-only">{description}</DialogDescription>
        <Command loop {...commandProps}>
          {children}
        </Command>
      </DialogContent>
    </Dialog>
  );
}

export function CommandInput({
  className,
  wrapperClassName,
  ...props
}: React.ComponentProps<typeof CommandPrimitive.Input> & { wrapperClassName?: string }) {
  return (
    <div className={cn("flex items-center gap-2 border-b border-border px-3", wrapperClassName)} cmdk-input-wrapper="">
      <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden />
      <CommandPrimitive.Input
        className={cn(
          "flex h-11 w-full bg-transparent py-3 text-sm outline-none placeholder:text-subtle-foreground disabled:cursor-not-allowed disabled:opacity-50",
          className
        )}
        {...props}
      />
    </div>
  );
}

export function CommandList({ className, ...props }: React.ComponentProps<typeof CommandPrimitive.List>) {
  return (
    <CommandPrimitive.List
      className={cn("max-h-[min(60vh,380px)] scroll-py-1 overflow-y-auto overflow-x-hidden p-1", className)}
      {...props}
    />
  );
}

export function CommandEmpty({ className, ...props }: React.ComponentProps<typeof CommandPrimitive.Empty>) {
  return (
    <CommandPrimitive.Empty
      className={cn("py-8 text-center text-[13px] text-muted-foreground", className)}
      {...props}
    />
  );
}

export function CommandLoading({ className, ...props }: React.ComponentProps<typeof CommandPrimitive.Loading>) {
  return <CommandPrimitive.Loading className={cn("py-3 text-center text-[13px] text-muted-foreground", className)} {...props} />;
}

export function CommandGroup({ className, ...props }: React.ComponentProps<typeof CommandPrimitive.Group>) {
  return (
    <CommandPrimitive.Group
      className={cn(
        "overflow-hidden p-1 text-foreground [&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:pb-1 [&_[cmdk-group-heading]]:pt-1.5 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:font-medium [&_[cmdk-group-heading]]:text-muted-foreground",
        className
      )}
      {...props}
    />
  );
}

export function CommandSeparator({ className, ...props }: React.ComponentProps<typeof CommandPrimitive.Separator>) {
  return <CommandPrimitive.Separator className={cn("-mx-1 h-px bg-border", className)} {...props} />;
}

export function CommandItem({ className, ...props }: React.ComponentProps<typeof CommandPrimitive.Item>) {
  return (
    <CommandPrimitive.Item
      className={cn(
        "relative flex cursor-default select-none items-center gap-2.5 rounded-md px-2 py-2 text-[13px] outline-none data-[disabled=true]:pointer-events-none data-[selected=true]:bg-accent data-[disabled=true]:opacity-50 [&_svg]:size-4 [&_svg]:shrink-0 [&_svg]:text-muted-foreground",
        className
      )}
      {...props}
    />
  );
}

export function CommandShortcut({ className, ...props }: React.ComponentProps<"span">) {
  return <span className={cn("ml-auto text-xs text-subtle-foreground", className)} {...props} />;
}
