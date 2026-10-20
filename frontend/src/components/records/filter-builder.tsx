"use client";

import * as React from "react";
import { format, isValid, parseISO } from "date-fns";
import { ListFilter, Trash2, X } from "lucide-react";

import { LookupValue } from "@/components/records/field-value";
import { LookupInput } from "@/components/records/inputs/lookup-input";
import { Button } from "@/components/ui/button";
import { Combobox } from "@/components/ui/combobox";
import { DatePicker } from "@/components/ui/date-picker";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { lookupTarget, operatorLabel, operatorsFor, optionLabel, selectOptions } from "@/lib/metadata/fields";
import { formatDate, formatDateTime } from "@/lib/metadata/format";
import { cn } from "@/lib/utils";
import type { FieldDefinition } from "@/types/metadata";
import type { FilterOperator, RecordFilter } from "@/types/records";

function defaultValue(field: FieldDefinition, op: FilterOperator) {
  if (op === "exists") return "true";
  if (field.type === "BOOLEAN") return "true";
  return "";
}

function FilterValueInput({
  orgId,
  field,
  filter,
  onChange,
}: {
  orgId: string;
  field: FieldDefinition;
  filter: RecordFilter;
  onChange: (value: string) => void;
}) {
  const { op, value } = filter;
  if (op === "exists") {
    return (
      <Select value={value || "true"} onValueChange={onChange}>
        <SelectTrigger size="sm" aria-label="Value">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="true">has a value</SelectItem>
          <SelectItem value="false">is empty</SelectItem>
        </SelectContent>
      </Select>
    );
  }
  if (field.type === "BOOLEAN") {
    return (
      <Select value={value || "true"} onValueChange={onChange}>
        <SelectTrigger size="sm" aria-label="Value">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="true">Yes</SelectItem>
          <SelectItem value="false">No</SelectItem>
        </SelectContent>
      </Select>
    );
  }
  if (field.type === "SINGLE_SELECT" || field.type === "MULTI_SELECT") {
    const options = selectOptions(field);
    if (op === "in") {
      const selected = value ? value.split(",") : [];
      return (
        <div className="flex max-h-40 flex-col gap-0.5 overflow-y-auto rounded-md border border-input p-1">
          {options.map((option) => {
            const checked = selected.includes(option.value);
            return (
              <label
                key={option.value}
                className="flex cursor-pointer items-center gap-2 rounded-sm px-1.5 py-1 text-[13px] hover:bg-accent"
              >
                <input
                  type="checkbox"
                  className="accent-[var(--primary)]"
                  checked={checked}
                  onChange={() =>
                    onChange((checked ? selected.filter((v) => v !== option.value) : [...selected, option.value]).join(","))
                  }
                />
                {option.label}
              </label>
            );
          })}
        </div>
      );
    }
    return (
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger size="sm" aria-label="Value">
          <SelectValue placeholder="Pick an option" />
        </SelectTrigger>
        <SelectContent>
          {options.map((option) => (
            <SelectItem key={option.value} value={option.value}>
              {option.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    );
  }
  if (field.type === "LOOKUP" && op !== "in") {
    const target = lookupTarget(field);
    if (target) {
      return (
        <LookupInput
          orgId={orgId}
          targetModuleKey={target}
          value={value || null}
          onChange={(next) => onChange(next ?? "")}
        />
      );
    }
  }
  if (field.type === "DATE") {
    return <DatePicker value={value || null} onChange={(next) => onChange(next ?? "")} clearable={false} />;
  }
  if (field.type === "DATETIME") {
    const local = value && isValid(parseISO(value)) ? format(parseISO(value), "yyyy-MM-dd'T'HH:mm") : "";
    return (
      <Input
        type="datetime-local"
        aria-label="Value"
        className="h-8 text-[13px]"
        value={local}
        onChange={(event) => onChange(event.target.value ? new Date(event.target.value).toISOString() : "")}
      />
    );
  }
  const numeric = field.type === "INTEGER" || field.type === "DECIMAL" || field.type === "CURRENCY";
  return (
    <Input
      aria-label="Value"
      className="h-8 text-[13px]"
      inputMode={numeric ? "decimal" : undefined}
      placeholder={op === "in" ? "Comma-separated values" : numeric ? "Amount" : "Value"}
      value={value}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}

export function FilterChipValue({ orgId, field, filter }: { orgId: string; field: FieldDefinition; filter: RecordFilter }) {
  if (filter.op === "exists") return <>{filter.value === "false" ? "is empty" : "has a value"}</>;
  if (field.type === "BOOLEAN") return <>{filter.value === "true" ? "Yes" : "No"}</>;
  if (field.type === "SINGLE_SELECT" || field.type === "MULTI_SELECT") {
    return <>{filter.value.split(",").map((v) => optionLabel(field, v)).join(", ")}</>;
  }
  if (field.type === "LOOKUP" && filter.op !== "in" && lookupTarget(field)) {
    return <LookupValue orgId={orgId} targetModuleKey={lookupTarget(field)!} id={filter.value} link={false} />;
  }
  if (field.type === "DATE") return <>{formatDate(filter.value)}</>;
  if (field.type === "DATETIME") return <>{formatDateTime(filter.value)}</>;
  return <>{filter.value}</>;
}

export interface FilterBuilderProps {
  orgId: string;
  fields: FieldDefinition[];
  filters: RecordFilter[];
  onApply: (filters: RecordFilter[]) => void;
}

/** Popover to compose `filter[field][op]=value` conditions, offering only operators valid for each field type. */
export function FilterBuilder({ orgId, fields, filters, onApply }: FilterBuilderProps) {
  const [open, setOpen] = React.useState(false);
  const [drafts, setDrafts] = React.useState<RecordFilter[]>(filters);
  const byKey = React.useMemo(() => new Map(fields.map((field) => [field.apiKey, field])), [fields]);

  const addFilter = (fieldKey: string | null) => {
    const field = fieldKey ? byKey.get(fieldKey) : undefined;
    if (!field) return;
    const op = operatorsFor(field.type)[0];
    setDrafts((current) => [...current, { field: field.apiKey, op, value: defaultValue(field, op) }]);
  };

  const updateDraft = (index: number, patch: Partial<RecordFilter>) =>
    setDrafts((current) => current.map((draft, i) => (i === index ? { ...draft, ...patch } : draft)));

  const apply = () => {
    onApply(drafts.filter((draft) => draft.op === "exists" || draft.value !== ""));
    setOpen(false);
  };

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        if (next) setDrafts(filters.length ? filters : []);
        setOpen(next);
      }}
    >
      <PopoverTrigger asChild>
        <Button variant="outline" size="sm" className={cn(filters.length > 0 && "border-primary/40 text-primary")}>
          <ListFilter />
          Filter
          {filters.length > 0 ? (
            <span className="grid min-w-4 place-items-center rounded-sm bg-primary px-1 text-[11px] font-semibold text-primary-foreground tabular">
              {filters.length}
            </span>
          ) : null}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-[min(36rem,calc(100vw-2rem))] p-0">
        <div className="flex flex-col gap-2 p-3">
          {drafts.length === 0 ? (
            <p className="px-1 py-2 text-[13px] text-muted-foreground">
              No filters yet. Add one to narrow the list down.
            </p>
          ) : (
            drafts.map((draft, index) => {
              const field = byKey.get(draft.field);
              if (!field) return null;
              return (
                <div
                  key={`${draft.field}-${index}`}
                  className="grid grid-cols-[minmax(0,8rem)_minmax(0,8.5rem)_minmax(0,1fr)_auto] items-start gap-2"
                >
                  <div className="flex h-8 items-center truncate rounded-md bg-muted px-2.5 text-[13px] font-medium">
                    {field.label}
                  </div>
                  <Select
                    value={draft.op}
                    onValueChange={(op) =>
                      updateDraft(index, { op: op as FilterOperator, value: defaultValue(field, op as FilterOperator) })
                    }
                  >
                    <SelectTrigger size="sm" aria-label="Operator">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {operatorsFor(field.type).map((op) => (
                        <SelectItem key={op} value={op}>
                          {operatorLabel(op, field.type)}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FilterValueInput
                    orgId={orgId}
                    field={field}
                    filter={draft}
                    onChange={(value) => updateDraft(index, { value })}
                  />
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    aria-label={`Remove ${field.label} filter`}
                    onClick={() => setDrafts((current) => current.filter((_, i) => i !== index))}
                  >
                    <Trash2 />
                  </Button>
                </div>
              );
            })
          )}
          <Combobox
            value={null}
            onChange={addFilter}
            clearable={false}
            className="w-48"
            placeholder="Add a filter"
            searchPlaceholder="Filter by…"
            options={fields.map((field) => ({ value: field.apiKey, label: field.label }))}
          />
        </div>
        <div className="flex items-center justify-between border-t border-border px-3 py-2.5">
          <Button variant="ghost" size="sm" onClick={() => setDrafts([])} disabled={drafts.length === 0}>
            Clear all
          </Button>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button size="sm" onClick={apply}>
              Apply filters
            </Button>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}

export function FilterChips({
  orgId,
  fields,
  filters,
  onChange,
}: {
  orgId: string;
  fields: FieldDefinition[];
  filters: RecordFilter[];
  onChange: (filters: RecordFilter[]) => void;
}) {
  const byKey = new Map(fields.map((field) => [field.apiKey, field]));
  return (
    <>
      {filters.map((filter, index) => {
        const field = byKey.get(filter.field);
        return (
          <span
            key={`${filter.field}-${filter.op}-${index}`}
            className="inline-flex h-7 max-w-72 items-center gap-1 rounded-md border border-border bg-surface pl-2 pr-0.5 text-xs"
          >
            <span className="font-medium">{field?.label ?? filter.field}</span>
            <span className="text-muted-foreground">
              {field && filter.op !== "exists" ? operatorLabel(filter.op, field.type) : null}
            </span>
            <span className="truncate font-medium">
              {field ? <FilterChipValue orgId={orgId} field={field} filter={filter} /> : filter.value}
            </span>
            <button
              type="button"
              aria-label="Remove filter"
              onClick={() => onChange(filters.filter((_, i) => i !== index))}
              className="focus-ring ml-0.5 rounded-sm p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              <X className="size-3" />
            </button>
          </span>
        );
      })}
      {filters.length > 1 ? (
        <Button variant="subtle" size="xs" onClick={() => onChange([])}>
          Clear
        </Button>
      ) : null}
    </>
  );
}
