import * as React from "react";

import { cn } from "@/lib/utils";
import { inputClassName } from "@/components/ui/input";

export function Textarea({ className, ...props }: React.ComponentProps<"textarea">) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(inputClassName, "min-h-20 resize-y py-2 leading-relaxed", className)}
      {...props}
    />
  );
}
