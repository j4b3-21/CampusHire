import type { FieldDefinition } from "@/types/metadata";

export type Tone = "neutral" | "indigo" | "green" | "amber" | "red" | "sky" | "violet" | "pink" | "teal";

const POSITIVE = ["placed", "accepted", "published", "done", "passed", "joined", "won", "confirmed", "visited", "active", "completed", "offered", "yes", "verified"];
const NEGATIVE = ["rejected", "declined", "failed", "revoked", "cancelled", "canceled", "withdrawn", "inactive", "no_show", "lost", "blocked"];
const WAITING = ["pending", "draft", "open", "extended", "applied", "lead", "unplaced", "new"];
const MOVING = ["in_progress", "interview", "shortlisted", "contacted", "assessment", "registration_closed", "scheduled"];

const PALETTE: Tone[] = ["indigo", "sky", "violet", "teal", "pink", "amber", "green"];

/** Badge classes per tone; light and dark tuned to the zinc surfaces. */
export const TONE_CLASSES: Record<Tone, string> = {
  neutral: "bg-muted text-foreground/75 border-border",
  indigo: "bg-indigo-50 text-indigo-700 border-indigo-100 dark:bg-indigo-500/12 dark:text-indigo-300 dark:border-indigo-400/15",
  green: "bg-emerald-50 text-emerald-700 border-emerald-100 dark:bg-emerald-500/12 dark:text-emerald-300 dark:border-emerald-400/15",
  amber: "bg-amber-50 text-amber-700 border-amber-100 dark:bg-amber-500/12 dark:text-amber-300 dark:border-amber-400/15",
  red: "bg-rose-50 text-rose-700 border-rose-100 dark:bg-rose-500/12 dark:text-rose-300 dark:border-rose-400/15",
  sky: "bg-sky-50 text-sky-700 border-sky-100 dark:bg-sky-500/12 dark:text-sky-300 dark:border-sky-400/15",
  violet: "bg-violet-50 text-violet-700 border-violet-100 dark:bg-violet-500/12 dark:text-violet-300 dark:border-violet-400/15",
  pink: "bg-pink-50 text-pink-700 border-pink-100 dark:bg-pink-500/12 dark:text-pink-300 dark:border-pink-400/15",
  teal: "bg-teal-50 text-teal-700 border-teal-100 dark:bg-teal-500/12 dark:text-teal-300 dark:border-teal-400/15",
};

/** Semantic tone for well-known workflow values, otherwise a stable color per option position. */
export function optionTone(field: FieldDefinition, value: string): Tone {
  const normalized = value.toLowerCase();
  if (POSITIVE.includes(normalized)) return "green";
  if (NEGATIVE.includes(normalized)) return "red";
  if (WAITING.includes(normalized)) return "neutral";
  if (MOVING.includes(normalized)) return "indigo";
  const options = field.config?.options ?? [];
  const index = options.findIndex((option) => option.value === value);
  if (index >= 0) return PALETTE[index % PALETTE.length];
  let hash = 0;
  for (const char of value) hash = (hash * 31 + char.charCodeAt(0)) | 0;
  return PALETTE[Math.abs(hash) % PALETTE.length];
}
