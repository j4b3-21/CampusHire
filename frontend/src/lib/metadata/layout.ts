import type { FieldDefinition, FieldType, ModuleDefinition } from "@/types/metadata";

export interface ResolvedSection {
  key: string;
  label: string;
  columns: number;
  fields: FieldDefinition[];
}

export interface ResolvedTab {
  key: string;
  label: string;
  sections: ResolvedSection[];
}

const WIDE_TYPES: FieldType[] = ["LONG_TEXT", "JSON", "MULTI_SELECT", "FILE_REFERENCE", "IMAGE_REFERENCE"];

/** Field types that take the full row in multi-column sections. */
export function isWideField(field: FieldDefinition) {
  return WIDE_TYPES.includes(field.type);
}

/**
 * Turns the module layout (`{sections: [{key, label, tab?, columns?, fields}]}`) into tabs of sections containing only
 * the given (visible) fields. Fields missing from the layout are appended in an "Other fields" section so nothing
 * required is ever unreachable. Without a layout, everything goes into one two-column section.
 */
export function resolveLayout(module: ModuleDefinition, fields: FieldDefinition[]): ResolvedTab[] {
  const byKey = new Map(fields.map((field) => [field.apiKey, field]));
  const placed = new Set<string>();
  const tabs: ResolvedTab[] = [];
  const tabFor = (label: string) => {
    let tab = tabs.find((t) => t.label === label);
    if (!tab) {
      tab = { key: label.toLowerCase().replace(/[^a-z0-9]+/g, "-") || "details", label, sections: [] };
      tabs.push(tab);
    }
    return tab;
  };

  for (const section of module.layout?.sections ?? []) {
    const sectionFields = section.fields
      .map((key) => byKey.get(key))
      .filter((field): field is FieldDefinition => Boolean(field) && !placed.has(field!.apiKey));
    sectionFields.forEach((field) => placed.add(field.apiKey));
    if (sectionFields.length === 0) continue;
    tabFor(section.tab?.trim() || "Details").sections.push({
      key: section.key,
      label: section.label,
      columns: Math.min(Math.max(section.columns ?? 2, 1), 3),
      fields: sectionFields,
    });
  }

  const remaining = fields.filter((field) => !placed.has(field.apiKey));
  if (remaining.length > 0) {
    const hasLayout = tabs.length > 0;
    const target = hasLayout ? tabs[0] : tabFor("Details");
    target.sections.push({
      key: hasLayout ? "other_fields" : "details",
      label: hasLayout ? "Other fields" : "Details",
      columns: 2,
      fields: remaining,
    });
  }
  return tabs;
}

export function gridColumnsClass(columns: number) {
  if (columns >= 3) return "sm:grid-cols-2 lg:grid-cols-3";
  if (columns === 2) return "sm:grid-cols-2";
  return "grid-cols-1";
}
