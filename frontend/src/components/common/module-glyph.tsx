import * as React from "react";

import { moduleIcon } from "@/lib/navigation";

/** The navigation icon for a module key (unknown modules get a generic icon). */
export function ModuleGlyph({ moduleKey, className }: { moduleKey: string; className?: string }) {
  return React.createElement(moduleIcon(moduleKey), { className, "aria-hidden": true });
}
