import type { CSSProperties } from "react";

import type { MyAccess } from "@/types/org";
import type { Workflow, WorkflowCategory, WorkflowState, WorkflowTransitionDefinition } from "@/types/workflow";

export const ANY_ROLE = "*";

export const CATEGORY_LABELS: Record<WorkflowCategory, string> = {
  OPEN: "Open",
  IN_PROGRESS: "In progress",
  WON: "Won",
  LOST: "Lost",
};

export const CATEGORY_DESCRIPTIONS: Record<WorkflowCategory, string> = {
  OPEN: "Waiting to be picked up",
  IN_PROGRESS: "Being worked on",
  WON: "Finished successfully",
  LOST: "Closed without success",
};

interface ColorClasses {
  dot: string;
  badge: string;
  /** Column header tint on the board. */
  soft: string;
}

/** Literal class names so Tailwind picks them up. */
export const STATE_COLORS: Record<string, ColorClasses> = {
  slate: {
    dot: "bg-slate-400",
    badge: "bg-slate-100 text-slate-700 border-slate-200 dark:bg-slate-500/15 dark:text-slate-300 dark:border-slate-400/20",
    soft: "bg-slate-50 dark:bg-slate-500/8",
  },
  zinc: {
    dot: "bg-zinc-400",
    badge: "bg-zinc-100 text-zinc-700 border-zinc-200 dark:bg-zinc-500/15 dark:text-zinc-300 dark:border-zinc-400/20",
    soft: "bg-zinc-50 dark:bg-zinc-500/8",
  },
  red: {
    dot: "bg-red-500",
    badge: "bg-red-50 text-red-700 border-red-100 dark:bg-red-500/12 dark:text-red-300 dark:border-red-400/15",
    soft: "bg-red-50/60 dark:bg-red-500/6",
  },
  orange: {
    dot: "bg-orange-500",
    badge: "bg-orange-50 text-orange-700 border-orange-100 dark:bg-orange-500/12 dark:text-orange-300 dark:border-orange-400/15",
    soft: "bg-orange-50/60 dark:bg-orange-500/6",
  },
  amber: {
    dot: "bg-amber-500",
    badge: "bg-amber-50 text-amber-700 border-amber-100 dark:bg-amber-500/12 dark:text-amber-300 dark:border-amber-400/15",
    soft: "bg-amber-50/60 dark:bg-amber-500/6",
  },
  yellow: {
    dot: "bg-yellow-500",
    badge: "bg-yellow-50 text-yellow-800 border-yellow-100 dark:bg-yellow-500/12 dark:text-yellow-300 dark:border-yellow-400/15",
    soft: "bg-yellow-50/60 dark:bg-yellow-500/6",
  },
  lime: {
    dot: "bg-lime-500",
    badge: "bg-lime-50 text-lime-800 border-lime-100 dark:bg-lime-500/12 dark:text-lime-300 dark:border-lime-400/15",
    soft: "bg-lime-50/60 dark:bg-lime-500/6",
  },
  green: {
    dot: "bg-green-500",
    badge: "bg-green-50 text-green-700 border-green-100 dark:bg-green-500/12 dark:text-green-300 dark:border-green-400/15",
    soft: "bg-green-50/60 dark:bg-green-500/6",
  },
  emerald: {
    dot: "bg-emerald-500",
    badge: "bg-emerald-50 text-emerald-700 border-emerald-100 dark:bg-emerald-500/12 dark:text-emerald-300 dark:border-emerald-400/15",
    soft: "bg-emerald-50/60 dark:bg-emerald-500/6",
  },
  teal: {
    dot: "bg-teal-500",
    badge: "bg-teal-50 text-teal-700 border-teal-100 dark:bg-teal-500/12 dark:text-teal-300 dark:border-teal-400/15",
    soft: "bg-teal-50/60 dark:bg-teal-500/6",
  },
  cyan: {
    dot: "bg-cyan-500",
    badge: "bg-cyan-50 text-cyan-700 border-cyan-100 dark:bg-cyan-500/12 dark:text-cyan-300 dark:border-cyan-400/15",
    soft: "bg-cyan-50/60 dark:bg-cyan-500/6",
  },
  sky: {
    dot: "bg-sky-500",
    badge: "bg-sky-50 text-sky-700 border-sky-100 dark:bg-sky-500/12 dark:text-sky-300 dark:border-sky-400/15",
    soft: "bg-sky-50/60 dark:bg-sky-500/6",
  },
  blue: {
    dot: "bg-blue-500",
    badge: "bg-blue-50 text-blue-700 border-blue-100 dark:bg-blue-500/12 dark:text-blue-300 dark:border-blue-400/15",
    soft: "bg-blue-50/60 dark:bg-blue-500/6",
  },
  indigo: {
    dot: "bg-indigo-500",
    badge: "bg-indigo-50 text-indigo-700 border-indigo-100 dark:bg-indigo-500/12 dark:text-indigo-300 dark:border-indigo-400/15",
    soft: "bg-indigo-50/60 dark:bg-indigo-500/6",
  },
  violet: {
    dot: "bg-violet-500",
    badge: "bg-violet-50 text-violet-700 border-violet-100 dark:bg-violet-500/12 dark:text-violet-300 dark:border-violet-400/15",
    soft: "bg-violet-50/60 dark:bg-violet-500/6",
  },
  purple: {
    dot: "bg-purple-500",
    badge: "bg-purple-50 text-purple-700 border-purple-100 dark:bg-purple-500/12 dark:text-purple-300 dark:border-purple-400/15",
    soft: "bg-purple-50/60 dark:bg-purple-500/6",
  },
  fuchsia: {
    dot: "bg-fuchsia-500",
    badge: "bg-fuchsia-50 text-fuchsia-700 border-fuchsia-100 dark:bg-fuchsia-500/12 dark:text-fuchsia-300 dark:border-fuchsia-400/15",
    soft: "bg-fuchsia-50/60 dark:bg-fuchsia-500/6",
  },
  pink: {
    dot: "bg-pink-500",
    badge: "bg-pink-50 text-pink-700 border-pink-100 dark:bg-pink-500/12 dark:text-pink-300 dark:border-pink-400/15",
    soft: "bg-pink-50/60 dark:bg-pink-500/6",
  },
  rose: {
    dot: "bg-rose-500",
    badge: "bg-rose-50 text-rose-700 border-rose-100 dark:bg-rose-500/12 dark:text-rose-300 dark:border-rose-400/15",
    soft: "bg-rose-50/60 dark:bg-rose-500/6",
  },
};

export const STATE_PALETTE = Object.keys(STATE_COLORS);

const CATEGORY_DEFAULT_COLOR: Record<WorkflowCategory, string> = {
  OPEN: "slate",
  IN_PROGRESS: "indigo",
  WON: "emerald",
  LOST: "red",
};

const HEX = /^#(?:[0-9a-f]{3}|[0-9a-f]{6})$/i;
const STATE_COLOR = /^[A-Za-z0-9#-]{1,32}$/;

export function isValidStateColor(color: string) {
  return STATE_COLOR.test(color);
}

export interface ResolvedStateColor {
  className: ColorClasses;
  /** Inline styles for hex colors (badge, dot). */
  style?: { badge: CSSProperties; dot: CSSProperties; soft: CSSProperties };
}

function expandHex(hex: string) {
  return hex.length === 4 ? `#${hex[1]}${hex[1]}${hex[2]}${hex[2]}${hex[3]}${hex[3]}` : hex;
}

/** Palette name, hex color or the category default. */
export function stateColor(state: Pick<WorkflowState, "color" | "category"> | undefined): ResolvedStateColor {
  const color = state?.color?.trim().toLowerCase();
  if (color && STATE_COLORS[color]) return { className: STATE_COLORS[color] };
  if (color && HEX.test(color)) {
    const hex = expandHex(color);
    return {
      className: { dot: "", badge: "", soft: "" },
      style: {
        badge: { color: hex, backgroundColor: `${hex}1a`, borderColor: `${hex}33` },
        dot: { backgroundColor: hex },
        soft: { backgroundColor: `${hex}0d` },
      },
    };
  }
  return { className: STATE_COLORS[CATEGORY_DEFAULT_COLOR[state?.category ?? "OPEN"]] };
}

export function stateByKey(workflow: Workflow | null | undefined, key: string | null | undefined) {
  if (!workflow || !key) return undefined;
  return workflow.definition.states.find((state) => state.key === key);
}

/** Board column order: as defined, with LOST states moved to the end. */
export function boardStates(workflow: Workflow) {
  const states = workflow.definition.states;
  return [...states.filter((s) => s.category !== "LOST"), ...states.filter((s) => s.category === "LOST")];
}

/** Whether the caller's role may use the transition (admins may use every defined transition). */
export function transitionPermitted(
  transition: WorkflowTransitionDefinition,
  access: Pick<MyAccess, "admin" | "role"> | null | undefined,
  isAdmin: boolean
) {
  if (isAdmin || access?.admin) return true;
  if (transition.roles.includes(ANY_ROLE)) return true;
  const roleKey = access?.role?.key;
  return Boolean(roleKey && transition.roles.includes(roleKey));
}

/** Transitions the caller may take from `state`, keyed by target state (first matching transition wins). */
export function allowedTargets(
  workflow: Workflow,
  state: string | null | undefined,
  access: Pick<MyAccess, "admin" | "role"> | null | undefined,
  isAdmin: boolean
) {
  const targets = new Map<string, WorkflowTransitionDefinition>();
  if (!state) {
    targets.set(workflow.definition.initial, {
      key: "__initial",
      label: "Set initial state",
      from: [],
      to: workflow.definition.initial,
      roles: [ANY_ROLE],
    });
    return targets;
  }
  for (const transition of workflow.definition.transitions) {
    if (!transition.from.includes(state) || transition.to === state) continue;
    if (!transitionPermitted(transition, access, isAdmin)) continue;
    if (!targets.has(transition.to)) targets.set(transition.to, transition);
  }
  return targets;
}

/** Every distinct target state reachable from at least one of `states` (bulk moves). */
export function reachableTargets(workflow: Workflow, isAdmin: boolean, access: Pick<MyAccess, "admin" | "role"> | null | undefined) {
  const keys = new Set<string>();
  for (const transition of workflow.definition.transitions) {
    if (transitionPermitted(transition, access, isAdmin)) keys.add(transition.to);
  }
  return workflow.definition.states.filter((state) => keys.has(state.key));
}
