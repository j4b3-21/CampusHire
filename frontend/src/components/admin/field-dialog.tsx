"use client";

import * as React from "react";
import { toast } from "sonner";
import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react";

import { API_KEY_PATTERN, FieldMessage, toApiKey } from "@/components/admin/admin-gate";
import { useOrg } from "@/components/app/org-context";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { ApiError, useAddField, useUpdateField } from "@/lib/api";
import { violationMap } from "@/lib/api/violations";
import { cn } from "@/lib/utils";
import type { JsonValue } from "@/types/api";
import { FIELD_TYPES, type FieldConfig, type FieldDefinition, type FieldInput, type FieldType, type ModuleDefinition, type SelectOption } from "@/types/metadata";

export const FIELD_TYPE_LABELS: Record<FieldType, string> = {
  TEXT: "Text",
  LONG_TEXT: "Long text",
  INTEGER: "Whole number",
  DECIMAL: "Decimal",
  BOOLEAN: "Yes / no",
  DATE: "Date",
  DATETIME: "Date and time",
  EMAIL: "Email",
  PHONE: "Phone",
  URL: "Link",
  CURRENCY: "Money",
  SINGLE_SELECT: "Pick-list",
  MULTI_SELECT: "Multi pick-list",
  JSON: "JSON",
  FILE_REFERENCE: "File",
  IMAGE_REFERENCE: "Image",
  LOOKUP: "Lookup",
};

const TEXT_TYPES: FieldType[] = ["TEXT", "LONG_TEXT", "EMAIL", "PHONE", "URL"];
const RANGE_TYPES: FieldType[] = ["INTEGER", "DECIMAL", "CURRENCY"];
const SELECT_TYPES: FieldType[] = ["SINGLE_SELECT", "MULTI_SELECT"];
const UNSORTABLE: FieldType[] = ["LONG_TEXT", "JSON", "MULTI_SELECT", "FILE_REFERENCE", "IMAGE_REFERENCE"];
const SEARCHABLE: FieldType[] = ["TEXT", "LONG_TEXT", "EMAIL", "PHONE", "URL"];
const FILTERLESS: FieldType[] = ["JSON", "FILE_REFERENCE", "IMAGE_REFERENCE"];

interface OptionDraft extends SelectOption {
  uid: string;
  existing: boolean;
}

interface FieldDraft {
  apiKey: string;
  keyEdited: boolean;
  label: string;
  description: string;
  type: FieldType;
  required: boolean;
  unique: boolean;
  readOnly: boolean;
  sensitive: boolean;
  searchable: boolean;
  filterable: boolean;
  sortable: boolean;
  minLength: string;
  maxLength: string;
  min: string;
  max: string;
  scale: string;
  currencies: string;
  maxSelections: string;
  maxBytes: string;
  targetModuleKey: string;
  onTargetDelete: "RESTRICT" | "NONE";
  options: OptionDraft[];
  defaultValue: string;
}

let optionUid = 0;

function toDraft(field: FieldDefinition | null): FieldDraft {
  const c: FieldConfig = field?.config ?? {};
  const str = (v: number | string | undefined) => (v === undefined || v === null ? "" : String(v));
  return {
    apiKey: field?.apiKey ?? "",
    keyEdited: Boolean(field),
    label: field?.label ?? "",
    description: field?.description ?? "",
    type: field?.type ?? "TEXT",
    required: field?.required ?? false,
    unique: field?.unique ?? false,
    readOnly: field?.readOnly ?? false,
    sensitive: field?.sensitive ?? false,
    searchable: field?.searchable ?? false,
    filterable: field?.filterable ?? true,
    sortable: field?.sortable ?? false,
    minLength: str(c.minLength),
    maxLength: str(c.maxLength),
    min: str(c.min),
    max: str(c.max),
    scale: str(c.scale),
    currencies: (c.currencies ?? []).join(", "),
    maxSelections: str(c.maxSelections),
    maxBytes: str(c.maxBytes),
    targetModuleKey: c.targetModuleKey ?? "",
    onTargetDelete: c.onTargetDelete ?? "RESTRICT",
    options: (c.options ?? []).map((o) => ({ ...o, uid: `o${++optionUid}`, existing: true })),
    defaultValue:
      field?.defaultValue === undefined || field?.defaultValue === null
        ? ""
        : typeof field.defaultValue === "string"
          ? field.defaultValue
          : JSON.stringify(field.defaultValue),
  };
}

const int = (value: string) => (value.trim() === "" ? undefined : Number.parseInt(value, 10));
const dec = (value: string) => (value.trim() === "" ? undefined : value.trim());

function configFor(draft: FieldDraft): FieldConfig | null {
  const config: FieldConfig = {};
  if (TEXT_TYPES.includes(draft.type)) {
    config.minLength = int(draft.minLength);
    config.maxLength = int(draft.maxLength);
  }
  if (RANGE_TYPES.includes(draft.type)) {
    config.min = dec(draft.min);
    config.max = dec(draft.max);
  }
  if (draft.type === "DECIMAL") config.scale = int(draft.scale);
  if (draft.type === "CURRENCY") {
    const currencies = draft.currencies.split(",").map((c) => c.trim().toUpperCase()).filter(Boolean);
    if (currencies.length) config.currencies = currencies;
  }
  if (SELECT_TYPES.includes(draft.type)) {
    config.options = draft.options.map(({ value, label, deprecated }) => ({ value: value.trim(), label: label.trim(), ...(deprecated ? { deprecated: true } : {}) }));
  }
  if (draft.type === "MULTI_SELECT") config.maxSelections = int(draft.maxSelections);
  if (draft.type === "JSON") config.maxBytes = int(draft.maxBytes);
  if (draft.type === "LOOKUP") {
    config.targetModuleKey = draft.targetModuleKey || undefined;
    config.onTargetDelete = draft.onTargetDelete;
  }
  const cleaned = Object.fromEntries(Object.entries(config).filter(([, v]) => v !== undefined)) as FieldConfig;
  return Object.keys(cleaned).length ? cleaned : null;
}

function defaultFor(draft: FieldDraft): JsonValue | null {
  const value = draft.defaultValue.trim();
  if (!value) return null;
  switch (draft.type) {
    case "BOOLEAN":
      return value === "true";
    case "INTEGER":
      return Number.parseInt(value, 10);
    case "DECIMAL":
      return value;
    case "TEXT":
    case "LONG_TEXT":
    case "EMAIL":
    case "PHONE":
    case "URL":
    case "SINGLE_SELECT":
    case "DATE":
      return value;
    default:
      return null;
  }
}

const DEFAULTABLE: FieldType[] = ["TEXT", "LONG_TEXT", "EMAIL", "PHONE", "URL", "INTEGER", "DECIMAL", "BOOLEAN", "SINGLE_SELECT", "DATE"];

function Flag({
  id,
  label,
  hint,
  checked,
  onChange,
  disabled,
}: {
  id: string;
  label: string;
  hint: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <label htmlFor={id} className={cn("flex items-start gap-2.5 rounded-md border border-border p-2.5", disabled && "opacity-60")}>
      <Switch id={id} checked={checked} onCheckedChange={onChange} disabled={disabled} className="mt-0.5" />
      <span className="min-w-0">
        <span className="block text-[13px] font-medium">{label}</span>
        <span className="block text-xs text-muted-foreground">{hint}</span>
      </span>
    </label>
  );
}

function OptionsEditor({ options, onChange, errors }: { options: OptionDraft[]; onChange: (options: OptionDraft[]) => void; errors: Map<string, string[]> }) {
  const patch = (index: number, value: Partial<OptionDraft>) => onChange(options.map((o, i) => (i === index ? { ...o, ...value } : o)));
  const move = (index: number, delta: number) => {
    const next = [...options];
    const target = index + delta;
    if (target < 0 || target >= next.length) return;
    [next[index], next[target]] = [next[target], next[index]];
    onChange(next);
  };
  return (
    <div className="grid gap-2">
      <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] gap-2 text-xs font-medium text-muted-foreground">
        <span>Label</span>
        <span>Value (stored)</span>
        <span className="sr-only">Actions</span>
      </div>
      {options.map((option, index) => {
        const message = [...(errors.get(`config.options[${index}].value`) ?? []), ...(errors.get(`config.options[${index}].label`) ?? []), ...(errors.get(`config.options[${index}]`) ?? [])].join(" ");
        return (
          <div key={option.uid} className="grid gap-1">
            <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] items-center gap-2">
              <Input
                aria-label={`Option ${index + 1} label`}
                value={option.label}
                className={cn("h-8 text-[13px]", option.deprecated && "line-through opacity-60")}
                onChange={(event) =>
                  patch(index, { label: event.target.value, ...(option.existing ? {} : { value: toApiKey(event.target.value) }) })
                }
              />
              <Input
                aria-label={`Option ${index + 1} value`}
                value={option.value}
                disabled={option.existing}
                className="h-8 font-mono text-xs"
                onChange={(event) => patch(index, { value: event.target.value })}
              />
              <div className="flex items-center gap-0.5">
                <Button variant="ghost" size="icon-xs" aria-label="Move option up" disabled={index === 0} onClick={() => move(index, -1)}>
                  <ArrowUp />
                </Button>
                <Button variant="ghost" size="icon-xs" aria-label="Move option down" disabled={index === options.length - 1} onClick={() => move(index, 1)}>
                  <ArrowDown />
                </Button>
                {option.existing ? (
                  <Button
                    variant="ghost"
                    size="xs"
                    aria-pressed={Boolean(option.deprecated)}
                    onClick={() => patch(index, { deprecated: !option.deprecated })}
                  >
                    {option.deprecated ? "Restore" : "Deprecate"}
                  </Button>
                ) : (
                  <Button variant="ghost" size="icon-xs" aria-label="Remove option" onClick={() => onChange(options.filter((_, i) => i !== index))}>
                    <Trash2 />
                  </Button>
                )}
              </div>
            </div>
            {message ? <p className="text-xs text-destructive">{message}</p> : null}
          </div>
        );
      })}
      <Button
        variant="outline"
        size="xs"
        className="self-start"
        onClick={() => onChange([...options, { uid: `o${++optionUid}`, value: "", label: "", existing: false }])}
      >
        <Plus /> Add option
      </Button>
      <FieldMessage hint="Existing options keep their stored value. Deprecate options instead of removing them; records keep their value." />
    </div>
  );
}

export function FieldDialog({
  module,
  field,
  open,
  onOpenChange,
}: {
  module: ModuleDefinition;
  /** `null` to add a field. */
  field: FieldDefinition | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const { orgId, modules } = useOrg();
  const add = useAddField(orgId, module.apiKey);
  const update = useUpdateField(orgId, module.apiKey);
  const [draft, setDraft] = React.useState<FieldDraft>(() => toDraft(field));
  const [errors, setErrors] = React.useState<Map<string, string[]>>(new Map());
  const set = <K extends keyof FieldDraft>(key: K, value: FieldDraft[K]) => setDraft((d) => ({ ...d, [key]: value }));
  const editing = Boolean(field);
  const pending = add.isPending || update.isPending;
  const err = (path: string) => (errors.get(path) ?? []).join(" ") || undefined;
  const id = React.useId();

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const local = new Map<string, string[]>();
    if (!draft.label.trim()) local.set("label", ["Enter a label"]);
    if (!editing && !API_KEY_PATTERN.test(draft.apiKey)) local.set("apiKey", ["Lowercase letters, digits and underscores, starting with a letter"]);
    if (SELECT_TYPES.includes(draft.type) && draft.options.length === 0) local.set("config.options", ["Add at least one option"]);
    if (draft.type === "LOOKUP" && !draft.targetModuleKey) local.set("config.targetModuleKey", ["Pick the module to look up"]);
    if (local.size) {
      setErrors(local);
      return;
    }
    setErrors(new Map());
    const input: FieldInput = {
      label: draft.label.trim(),
      description: draft.description.trim() || null,
      required: draft.required,
      readOnly: draft.readOnly,
      sensitive: draft.sensitive,
      searchable: SEARCHABLE.includes(draft.type) ? draft.searchable : false,
      filterable: FILTERLESS.includes(draft.type) ? false : draft.filterable,
      sortable: UNSORTABLE.includes(draft.type) ? false : draft.sortable,
      config: configFor(draft),
      defaultValue: DEFAULTABLE.includes(draft.type) ? defaultFor(draft) : null,
    };
    try {
      if (field) {
        await update.mutateAsync({ fieldKey: field.apiKey, version: field.version, input });
        toast.success(`${input.label} saved`);
      } else {
        await add.mutateAsync({ ...input, apiKey: draft.apiKey, type: draft.type, unique: draft.unique });
        toast.success(`${input.label} added`);
      }
      onOpenChange(false);
    } catch (error) {
      if (error instanceof ApiError && error.isValidation) setErrors(violationMap(error));
      else if (error instanceof ApiError && error.code === "field_exists") setErrors(new Map([["apiKey", [error.friendlyMessage]]]));
    }
  };

  const generalErrors = [...errors.entries()].filter(
    ([path]) => !["label", "apiKey", "description", "type", "defaultValue"].includes(path) && !path.startsWith("config")
  );
  const configErrors = [...errors.entries()].filter(([path]) => path.startsWith("config") && !path.startsWith("config.options["));

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <form onSubmit={submit} className="grid gap-5" noValidate>
          <DialogHeader>
            <DialogTitle>{field ? `Edit ${field.label}` : `Add a field to ${module.labelPlural}`}</DialogTitle>
            <DialogDescription>
              {field ? "Type and key can't change once records may use them." : "Pick the type carefully; it can't be changed later."}
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-label`}>Label</Label>
              <Input
                id={`${id}-label`}
                autoFocus
                value={draft.label}
                aria-invalid={Boolean(err("label")) || undefined}
                onChange={(event) => {
                  const label = event.target.value;
                  setDraft((d) => ({ ...d, label, apiKey: d.keyEdited ? d.apiKey : toApiKey(label) }));
                }}
              />
              <FieldMessage error={err("label")} />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-key`}>Key</Label>
              <Input
                id={`${id}-key`}
                value={draft.apiKey}
                disabled={editing}
                className="font-mono text-[13px]"
                aria-invalid={Boolean(err("apiKey")) || undefined}
                onChange={(event) => setDraft((d) => ({ ...d, apiKey: event.target.value, keyEdited: true }))}
              />
              <FieldMessage error={err("apiKey")} hint={editing ? undefined : "Used in the API, filters and imports."} />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-type`}>Type</Label>
              <Select value={draft.type} disabled={editing} onValueChange={(type) => set("type", type as FieldType)}>
                <SelectTrigger id={`${id}-type`}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {FIELD_TYPES.map((type) => (
                    <SelectItem key={type} value={type}>
                      {FIELD_TYPE_LABELS[type]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <FieldMessage error={err("type")} />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-description`}>Help text</Label>
              <Input id={`${id}-description`} value={draft.description} onChange={(event) => set("description", event.target.value)} />
              <FieldMessage error={err("description")} />
            </div>
          </div>

          {/* type configuration */}
          {TEXT_TYPES.includes(draft.type) ? (
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-minlen`}>Minimum length</Label>
                <Input id={`${id}-minlen`} inputMode="numeric" value={draft.minLength} onChange={(e) => set("minLength", e.target.value)} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-maxlen`}>Maximum length</Label>
                <Input
                  id={`${id}-maxlen`}
                  inputMode="numeric"
                  placeholder={draft.type === "LONG_TEXT" ? "10000" : "255"}
                  value={draft.maxLength}
                  onChange={(e) => set("maxLength", e.target.value)}
                />
              </div>
            </div>
          ) : null}
          {RANGE_TYPES.includes(draft.type) ? (
            <div className={cn("grid gap-4", draft.type === "DECIMAL" || draft.type === "CURRENCY" ? "sm:grid-cols-3" : "sm:grid-cols-2")}>
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-min`}>Minimum{draft.type === "CURRENCY" ? " amount" : ""}</Label>
                <Input id={`${id}-min`} inputMode="decimal" value={draft.min} onChange={(e) => set("min", e.target.value)} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-max`}>Maximum{draft.type === "CURRENCY" ? " amount" : ""}</Label>
                <Input id={`${id}-max`} inputMode="decimal" value={draft.max} onChange={(e) => set("max", e.target.value)} />
              </div>
              {draft.type === "DECIMAL" ? (
                <div className="grid gap-1.5">
                  <Label htmlFor={`${id}-scale`}>Decimal places</Label>
                  <Input id={`${id}-scale`} inputMode="numeric" placeholder="6" value={draft.scale} onChange={(e) => set("scale", e.target.value)} />
                </div>
              ) : null}
              {draft.type === "CURRENCY" ? (
                <div className="grid gap-1.5">
                  <Label htmlFor={`${id}-cur`}>Currencies</Label>
                  <Input id={`${id}-cur`} placeholder="INR, USD (empty = any)" value={draft.currencies} onChange={(e) => set("currencies", e.target.value)} />
                </div>
              ) : null}
            </div>
          ) : null}
          {SELECT_TYPES.includes(draft.type) ? (
            <div className="grid gap-2">
              <Label>Options</Label>
              <OptionsEditor options={draft.options} onChange={(options) => set("options", options)} errors={errors} />
              <FieldMessage error={err("config.options")} />
              {draft.type === "MULTI_SELECT" ? (
                <div className="grid max-w-48 gap-1.5">
                  <Label htmlFor={`${id}-maxsel`}>Maximum selections</Label>
                  <Input id={`${id}-maxsel`} inputMode="numeric" value={draft.maxSelections} onChange={(e) => set("maxSelections", e.target.value)} />
                </div>
              ) : null}
            </div>
          ) : null}
          {draft.type === "JSON" ? (
            <div className="grid max-w-60 gap-1.5">
              <Label htmlFor={`${id}-bytes`}>Maximum size (bytes)</Label>
              <Input id={`${id}-bytes`} inputMode="numeric" placeholder="16384" value={draft.maxBytes} onChange={(e) => set("maxBytes", e.target.value)} />
            </div>
          ) : null}
          {draft.type === "LOOKUP" ? (
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-target`}>Looks up</Label>
                <Select value={draft.targetModuleKey} disabled={editing} onValueChange={(v) => set("targetModuleKey", v)}>
                  <SelectTrigger id={`${id}-target`} aria-invalid={Boolean(err("config.targetModuleKey")) || undefined}>
                    <SelectValue placeholder="Pick a module" />
                  </SelectTrigger>
                  <SelectContent>
                    {modules.map((m) => (
                      <SelectItem key={m.apiKey} value={m.apiKey}>
                        {m.labelPlural}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FieldMessage error={err("config.targetModuleKey")} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-ondelete`}>When the target is deleted</Label>
                <Select value={draft.onTargetDelete} onValueChange={(v) => set("onTargetDelete", v as FieldDraft["onTargetDelete"])}>
                  <SelectTrigger id={`${id}-ondelete`}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="RESTRICT">Block the delete</SelectItem>
                    <SelectItem value="NONE">Allow it (keep the reference)</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
          ) : null}
          {configErrors.length > 0 ? (
            <ul className="list-disc pl-4 text-xs text-destructive">
              {configErrors.map(([path, messages]) => (
                <li key={path}>
                  <code className="font-mono">{path}</code>: {messages.join(" ")}
                </li>
              ))}
            </ul>
          ) : null}

          {DEFAULTABLE.includes(draft.type) ? (
            <div className="grid max-w-sm gap-1.5">
              <Label htmlFor={`${id}-default`}>Default value</Label>
              {draft.type === "SINGLE_SELECT" ? (
                <Select value={draft.defaultValue || "__none"} onValueChange={(v) => set("defaultValue", v === "__none" ? "" : v)}>
                  <SelectTrigger id={`${id}-default`}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__none">No default</SelectItem>
                    {draft.options
                      .filter((o) => o.value)
                      .map((o) => (
                        <SelectItem key={o.uid} value={o.value}>
                          {o.label || o.value}
                        </SelectItem>
                      ))}
                  </SelectContent>
                </Select>
              ) : draft.type === "BOOLEAN" ? (
                <Select value={draft.defaultValue || "__none"} onValueChange={(v) => set("defaultValue", v === "__none" ? "" : v)}>
                  <SelectTrigger id={`${id}-default`}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__none">No default</SelectItem>
                    <SelectItem value="true">Yes</SelectItem>
                    <SelectItem value="false">No</SelectItem>
                  </SelectContent>
                </Select>
              ) : draft.type === "LONG_TEXT" ? (
                <Textarea id={`${id}-default`} value={draft.defaultValue} onChange={(e) => set("defaultValue", e.target.value)} />
              ) : (
                <Input
                  id={`${id}-default`}
                  type={draft.type === "DATE" ? "date" : "text"}
                  inputMode={draft.type === "INTEGER" || draft.type === "DECIMAL" ? "decimal" : undefined}
                  value={draft.defaultValue}
                  onChange={(e) => set("defaultValue", e.target.value)}
                />
              )}
              <FieldMessage
                error={err("defaultValue")}
                hint={draft.type === "SINGLE_SELECT" ? "A workflow on this field starts records in the default option." : undefined}
              />
            </div>
          ) : null}

          <div className="grid gap-2 sm:grid-cols-2">
            <Flag id={`${id}-required`} label="Required" hint="Records can't be saved without it" checked={draft.required} onChange={(v) => set("required", v)} />
            <Flag
              id={`${id}-unique`}
              label="Unique"
              hint={editing ? "Set when the field is created" : "No two records share a value"}
              checked={draft.unique}
              disabled={editing}
              onChange={(v) => set("unique", v)}
            />
            <Flag
              id={`${id}-searchable`}
              label="Searchable"
              hint="Matched by the search box"
              checked={draft.searchable && SEARCHABLE.includes(draft.type)}
              disabled={!SEARCHABLE.includes(draft.type)}
              onChange={(v) => set("searchable", v)}
            />
            <Flag
              id={`${id}-filterable`}
              label="Filterable"
              hint="Usable in filters, boards and role rules"
              checked={draft.filterable && !FILTERLESS.includes(draft.type)}
              disabled={FILTERLESS.includes(draft.type)}
              onChange={(v) => set("filterable", v)}
            />
            <Flag
              id={`${id}-sortable`}
              label="Sortable"
              hint="Tables can sort by it"
              checked={draft.sortable && !UNSORTABLE.includes(draft.type)}
              disabled={UNSORTABLE.includes(draft.type)}
              onChange={(v) => set("sortable", v)}
            />
            <Flag id={`${id}-sensitive`} label="Sensitive" hint="Masked in lists and audit details" checked={draft.sensitive} onChange={(v) => set("sensitive", v)} />
            <Flag id={`${id}-readonly`} label="Read-only" hint="Set by imports or automation only" checked={draft.readOnly} onChange={(v) => set("readOnly", v)} />
          </div>

          {generalErrors.length > 0 ? (
            <ul className="list-disc rounded-md border border-destructive/20 bg-destructive-soft py-2 pl-7 pr-3 text-xs text-destructive-soft-foreground">
              {generalErrors.map(([path, messages]) => (
                <li key={path}>
                  <code className="font-mono">{path}</code>: {messages.join(" ")}
                </li>
              ))}
            </ul>
          ) : null}

          <DialogFooter>
            {field ? <Badge variant="outline" className="mr-auto self-center">v{field.version}</Badge> : null}
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={pending}>
              {pending ? <Spinner className="text-primary-foreground" /> : null}
              {field ? "Save field" : "Add field"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
