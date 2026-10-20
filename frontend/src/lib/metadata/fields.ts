import type { FieldDefinition, FieldType, ModuleDefinition, SelectOption } from "@/types/metadata";
import type { ModuleCapabilities } from "@/types/org";
import type { FilterOperator, RecordData, RecordItem } from "@/types/records";
import { shortId } from "@/lib/metadata/format";

/** Fields that exist for clients (archived fields are hidden by the API but filtered defensively), in order. */
export function activeFields(module: ModuleDefinition) {
  return module.fields.filter((field) => field.status !== "ARCHIVED").sort((a, b) => a.position - b.position);
}

/** Fields the caller may see (role `hiddenFields` removed). */
export function visibleFields(module: ModuleDefinition, capabilities?: ModuleCapabilities | null) {
  const hidden = new Set(capabilities?.hiddenFields ?? []);
  return activeFields(module).filter((field) => !hidden.has(field.apiKey));
}

/** Whether the caller may set this field (definition read-only, deprecated, or role read-only). */
export function isFieldEditable(field: FieldDefinition, capabilities?: ModuleCapabilities | null) {
  if (field.readOnly || field.status === "DEPRECATED") return false;
  return !(capabilities?.readOnlyFields ?? []).includes(field.apiKey);
}

/**
 * The field used as a record's title: `settings.titleField` when the module sets one, otherwise the first
 * required + searchable TEXT field, then any required TEXT, then any TEXT/EMAIL field.
 */
export function displayField(module: ModuleDefinition): FieldDefinition | null {
  const fields = activeFields(module);
  const configured = module.settings?.titleField;
  const explicit = typeof configured === "string" ? fields.find((f) => f.apiKey === configured) : undefined;
  return (
    explicit ??
    fields.find((f) => f.type === "TEXT" && f.required && f.searchable) ??
    fields.find((f) => f.type === "TEXT" && f.required) ??
    fields.find((f) => f.type === "TEXT") ??
    fields.find((f) => f.type === "EMAIL") ??
    null
  );
}

export function recordTitle(module: ModuleDefinition | undefined, record: Pick<RecordItem, "id" | "data">) {
  const field = module ? displayField(module) : null;
  const value = field ? record.data[field.apiKey] : undefined;
  if (typeof value === "string" && value.trim()) return value;
  return `${module?.labelSingular ?? "Record"} ${shortId(record.id)}`;
}

export function dataTitle(module: ModuleDefinition | undefined, id: string, data: RecordData) {
  return recordTitle(module, { id, data });
}

export function selectOptions(field: FieldDefinition): SelectOption[] {
  return field.config?.options ?? [];
}

export function optionLabel(field: FieldDefinition, value: string) {
  return selectOptions(field).find((option) => option.value === value)?.label ?? value;
}

export function lookupTarget(field: FieldDefinition) {
  return field.config?.targetModuleKey ?? null;
}

export const NUMERIC_TYPES: FieldType[] = ["INTEGER", "DECIMAL", "CURRENCY"];
export const TEXTUAL_TYPES: FieldType[] = ["TEXT", "LONG_TEXT", "EMAIL", "PHONE", "URL"];

export function isNumericType(type: FieldType) {
  return NUMERIC_TYPES.includes(type);
}

/** Operators accepted by the record list endpoint per field type (see API guide, "Record filters"). */
export function operatorsFor(type: FieldType): FilterOperator[] {
  switch (type) {
    case "INTEGER":
    case "DECIMAL":
    case "DATE":
    case "DATETIME":
      return ["eq", "ne", "gt", "gte", "lt", "lte", "exists"];
    case "CURRENCY":
      return ["gte", "lte", "gt", "lt", "exists"];
    case "TEXT":
    case "LONG_TEXT":
    case "EMAIL":
    case "PHONE":
    case "URL":
      return ["contains", "eq", "ne", "in", "exists"];
    case "SINGLE_SELECT":
    case "LOOKUP":
      return ["eq", "ne", "in", "exists"];
    case "MULTI_SELECT":
      return ["contains", "exists"];
    case "BOOLEAN":
      return ["eq", "ne", "exists"];
    default:
      return [];
  }
}

export function operatorLabel(op: FilterOperator, type: FieldType) {
  const temporal = type === "DATE" || type === "DATETIME";
  switch (op) {
    case "eq":
      return "is";
    case "ne":
      return "is not";
    case "gt":
      return temporal ? "is after" : "greater than";
    case "gte":
      return temporal ? "on or after" : "at least";
    case "lt":
      return temporal ? "is before" : "less than";
    case "lte":
      return temporal ? "on or before" : "at most";
    case "contains":
      return type === "MULTI_SELECT" ? "includes" : "contains";
    case "in":
      return "is any of";
    case "exists":
      return "is set";
  }
}

export function filterableFields(module: ModuleDefinition, capabilities?: ModuleCapabilities | null) {
  return visibleFields(module, capabilities).filter((f) => f.filterable && operatorsFor(f.type).length > 0);
}

export function sortableFields(module: ModuleDefinition, capabilities?: ModuleCapabilities | null) {
  return visibleFields(module, capabilities).filter((f) => f.sortable);
}

export const SYSTEM_SORT_FIELDS = ["created_at", "updated_at"] as const;
