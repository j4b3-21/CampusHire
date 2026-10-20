import type { Instant, JsonObject, JsonValue, Uuid } from "@/types/api";

export const FIELD_TYPES = [
  "TEXT",
  "LONG_TEXT",
  "INTEGER",
  "DECIMAL",
  "BOOLEAN",
  "DATE",
  "DATETIME",
  "EMAIL",
  "PHONE",
  "URL",
  "CURRENCY",
  "SINGLE_SELECT",
  "MULTI_SELECT",
  "JSON",
  "FILE_REFERENCE",
  "IMAGE_REFERENCE",
  "LOOKUP",
] as const;

export type FieldType = (typeof FIELD_TYPES)[number];

export type FieldStatus = "ACTIVE" | "DEPRECATED" | "ARCHIVED";

export interface SelectOption {
  value: string;
  label: string;
  deprecated?: boolean;
}

/** Type-specific settings (`FieldConfig.java`); only the properties relevant to the field type are present. */
export interface FieldConfig {
  minLength?: number;
  maxLength?: number;
  /** BigDecimal, serialized as a JSON number. */
  min?: number | string;
  max?: number | string;
  scale?: number;
  options?: SelectOption[];
  maxSelections?: number;
  currencies?: string[];
  maxBytes?: number;
  targetModuleKey?: string;
  onTargetDelete?: "RESTRICT" | "NONE";
}

export interface FieldDefinition {
  id: Uuid;
  apiKey: string;
  label: string;
  description?: string | null;
  type: FieldType;
  status: FieldStatus;
  required: boolean;
  unique: boolean;
  readOnly: boolean;
  sensitive: boolean;
  searchable: boolean;
  filterable: boolean;
  sortable: boolean;
  defaultValue?: JsonValue | null;
  config?: FieldConfig | null;
  position: number;
  version: number;
  createdAt: Instant;
  updatedAt: Instant;
}

export interface LayoutSection {
  key: string;
  label: string;
  /** Optional tab name; sections sharing a tab are grouped. */
  tab?: string | null;
  columns?: number | null;
  fields: string[];
}

export interface ModuleLayout {
  sections: LayoutSection[];
}

export type ModuleSettings = JsonObject & {
  accessMode?: "MEMBERS" | "ROLES";
  /** Optional UI hint: api key of the field used as the record title. */
  titleField?: string;
  /** Optional UI hint: api key of a secondary field shown under the title. */
  subtitleField?: string;
  /** Optional UI hint: lucide icon name for the module. */
  icon?: string;
};

export interface ModuleDefinition {
  id: Uuid;
  apiKey: string;
  scope: "GLOBAL" | "TENANT";
  labelSingular: string;
  labelPlural: string;
  description?: string | null;
  status: "ACTIVE" | "ARCHIVED" | string;
  settings?: ModuleSettings | null;
  layout?: ModuleLayout | null;
  version: number;
  fields: FieldDefinition[];
  createdAt: Instant;
  updatedAt: Instant;
}

export type RelationshipCardinality = "ONE_TO_ONE" | "ONE_TO_MANY" | "MANY_TO_MANY";

export interface RelationshipDefinition {
  id: Uuid;
  apiKey: string;
  label: string;
  inverseLabel?: string | null;
  sourceModule: string;
  targetModule: string;
  cardinality: RelationshipCardinality | string;
  onDelete: string;
  status: string;
  version: number;
  createdAt: Instant;
}
