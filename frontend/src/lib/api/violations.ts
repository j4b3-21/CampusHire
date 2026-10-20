import { ApiError } from "@/lib/api/errors";

/** Server validation messages keyed by their full path (e.g. `definition.transitions[2].from`). */
export function violationMap(error: unknown): Map<string, string[]> {
  const map = new Map<string, string[]>();
  if (!(error instanceof ApiError)) return map;
  for (const violation of error.errors) {
    const list = map.get(violation.field) ?? [];
    list.push(violation.message);
    map.set(violation.field, list);
  }
  return map;
}

/** Messages for `prefix` itself and every nested path below it. */
export function violationsUnder(map: Map<string, string[]>, prefix: string): string[] {
  const result: string[] = [];
  for (const [path, messages] of map) {
    if (path === prefix || path.startsWith(`${prefix}.`) || path.startsWith(`${prefix}[`)) result.push(...messages);
  }
  return result;
}
