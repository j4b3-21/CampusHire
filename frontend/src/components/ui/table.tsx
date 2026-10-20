import * as React from "react";

import { cn } from "@/lib/utils";

export function Table({ className, ...props }: React.ComponentProps<"table">) {
  return (
    <div data-slot="table-container" className="relative w-full overflow-x-auto">
      <table className={cn("w-full caption-bottom border-separate border-spacing-0 text-[13px]", className)} {...props} />
    </div>
  );
}

export function TableHeader({ className, ...props }: React.ComponentProps<"thead">) {
  return <thead className={cn("[&_tr]:bg-surface", className)} {...props} />;
}

export function TableBody({ className, ...props }: React.ComponentProps<"tbody">) {
  return <tbody className={cn("[&_tr:last-child_td]:border-b-0", className)} {...props} />;
}

export function TableRow({ className, ...props }: React.ComponentProps<"tr">) {
  return (
    <tr
      className={cn(
        "group/row transition-colors data-[state=selected]:bg-primary-soft/60 hover:bg-surface [&>td]:border-b [&>td]:border-border",
        className
      )}
      {...props}
    />
  );
}

export function TableHead({ className, ...props }: React.ComponentProps<"th">) {
  return (
    <th
      className={cn(
        "h-9 whitespace-nowrap border-b border-border px-3 text-left align-middle text-xs font-medium text-muted-foreground first:pl-4 last:pr-4",
        className
      )}
      {...props}
    />
  );
}

export function TableCell({ className, ...props }: React.ComponentProps<"td">) {
  return <td className={cn("px-3 align-middle first:pl-4 last:pr-4", className)} {...props} />;
}
