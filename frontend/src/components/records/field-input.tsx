"use client";

import * as React from "react";
import { useController, type Control } from "react-hook-form";
import { Lock, X } from "lucide-react";

import { FileInput } from "@/components/records/inputs/file-input";
import { LookupInput } from "@/components/records/inputs/lookup-input";
import { MultiSelectInput } from "@/components/records/inputs/multi-select-input";
import { CurrencyInput, DateTimeInput, JsonInput } from "@/components/records/inputs/simple-inputs";
import { DatePicker } from "@/components/ui/date-picker";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { lookupTarget, selectOptions } from "@/lib/metadata/fields";
import { emptyFormValue, type CurrencyFormValue, type FormFieldValue, type RecordFormValues } from "@/lib/metadata/form";
import { isWideField } from "@/lib/metadata/layout";
import { cn } from "@/lib/utils";
import type { FieldDefinition } from "@/types/metadata";
import type { FileReference } from "@/types/records";

const asString = (value: FormFieldValue) => (typeof value === "string" ? value : "");

const TEXT_INPUT_TYPES: Partial<Record<FieldDefinition["type"], { type: string; inputMode?: React.HTMLAttributes<HTMLInputElement>["inputMode"]; autoComplete?: string; placeholder?: string }>> = {
  TEXT: { type: "text" },
  EMAIL: { type: "email", inputMode: "email", autoComplete: "email", placeholder: "name@example.com" },
  PHONE: { type: "tel", inputMode: "tel", autoComplete: "tel", placeholder: "+91 98765 43210" },
  URL: { type: "url", inputMode: "url", placeholder: "https://" },
  INTEGER: { type: "text", inputMode: "numeric", placeholder: "0" },
  DECIMAL: { type: "text", inputMode: "decimal", placeholder: "0.00" },
};

export interface FieldInputProps {
  orgId: string;
  field: FieldDefinition;
  control: Control<RecordFormValues>;
  disabled?: boolean;
  /** Why the field is locked, shown as a tooltip. */
  lockedReason?: string;
}

export function FieldInput({ orgId, field, control, disabled, lockedReason }: FieldInputProps) {
  const {
    field: { ref: inputRef, name, value: rawValue, onChange, onBlur },
    fieldState,
  } = useController({ name: field.apiKey, control });
  const id = `field-${field.apiKey}`;
  const errorId = `${id}-error`;
  const descriptionId = `${id}-description`;
  const invalid = Boolean(fieldState.error);
  const value = rawValue ?? emptyFormValue(field);
  const describedBy = [fieldState.error ? errorId : null, field.description ? descriptionId : null]
    .filter(Boolean)
    .join(" ") || undefined;
  const common = { id, disabled, onBlur: onBlur, "aria-invalid": invalid || undefined, "aria-describedby": describedBy };

  let control_: React.ReactNode;
  switch (field.type) {
    case "TEXT":
    case "EMAIL":
    case "PHONE":
    case "URL":
    case "INTEGER":
    case "DECIMAL": {
      const props = TEXT_INPUT_TYPES[field.type]!;
      control_ = (
        <Input
          {...common}
          ref={inputRef}
          name={name}
          type={props.type}
          inputMode={props.inputMode}
          autoComplete={props.autoComplete ?? "off"}
          placeholder={props.placeholder}
          value={asString(value)}
          onChange={(event) => onChange(event.target.value)}
          className={cn((field.type === "INTEGER" || field.type === "DECIMAL") && "tabular")}
        />
      );
      break;
    }
    case "LONG_TEXT":
      control_ = (
        <Textarea
          {...common}
          ref={inputRef}
          name={name}
          rows={4}
          value={asString(value)}
          onChange={(event) => onChange(event.target.value)}
        />
      );
      break;
    case "BOOLEAN":
      control_ = (
        <div className="flex h-9 items-center gap-2.5">
          <Switch
            id={id}
            ref={inputRef}
            checked={value === true}
            disabled={disabled}
            onCheckedChange={(checked) => onChange(checked)}
            onBlur={onBlur}
            aria-describedby={describedBy}
          />
          <span className="text-[13px] text-muted-foreground">{value === true ? "Yes" : "No"}</span>
        </div>
      );
      break;
    case "DATE":
      control_ = (
        <DatePicker
          id={id}
          value={asString(value) || null}
          disabled={disabled}
          invalid={invalid}
          clearable={!field.required}
          onBlur={onBlur}
          onChange={(next) => onChange(next ?? "")}
        />
      );
      break;
    case "DATETIME":
      control_ = (
        <DateTimeInput
          id={id}
          value={asString(value)}
          clearable={!field.required}
          disabled={disabled}
          invalid={invalid}
          onBlur={onBlur}
          onChange={onChange}
        />
      );
      break;
    case "CURRENCY":
      control_ = (
        <CurrencyInput
          id={id}
          value={value as CurrencyFormValue}
          currencies={field.config?.currencies}
          disabled={disabled}
          invalid={invalid}
          onBlur={onBlur}
          onChange={onChange}
        />
      );
      break;
    case "SINGLE_SELECT": {
      const current = asString(value);
      const options = selectOptions(field).filter((option) => !option.deprecated || option.value === current);
      control_ = (
        <div className="relative">
          <Select value={current} onValueChange={(next) => onChange(next)} disabled={disabled}>
            <SelectTrigger
              id={id}
              ref={inputRef}
              onBlur={onBlur}
              aria-invalid={invalid || undefined}
              aria-describedby={describedBy}
              className={cn(current && !field.required && !disabled && "pr-14")}
            >
              <SelectValue placeholder="Select…" />
            </SelectTrigger>
            <SelectContent>
              {options.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {current && !field.required && !disabled ? (
            <button
              type="button"
              aria-label={`Clear ${field.label}`}
              onClick={() => onChange("")}
              className="focus-ring absolute right-8 top-1/2 -translate-y-1/2 rounded-sm p-0.5 text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              <X className="size-3.5" />
            </button>
          ) : null}
        </div>
      );
      break;
    }
    case "MULTI_SELECT":
      control_ = (
        <MultiSelectInput
          id={id}
          field={field}
          value={Array.isArray(value) ? value : []}
          disabled={disabled}
          invalid={invalid}
          onBlur={onBlur}
          onChange={onChange}
        />
      );
      break;
    case "JSON":
      control_ = (
        <JsonInput
          id={id}
          value={asString(value)}
          disabled={disabled}
          invalid={invalid}
          onBlur={onBlur}
          onChange={onChange}
        />
      );
      break;
    case "FILE_REFERENCE":
    case "IMAGE_REFERENCE":
      control_ = (
        <FileInput
          id={id}
          orgId={orgId}
          image={field.type === "IMAGE_REFERENCE"}
          value={(value as FileReference | null) ?? null}
          disabled={disabled}
          invalid={invalid}
          onBlur={onBlur}
          onChange={onChange}
        />
      );
      break;
    case "LOOKUP": {
      const target = lookupTarget(field);
      control_ = target ? (
        <LookupInput
          id={id}
          orgId={orgId}
          targetModuleKey={target}
          value={asString(value) || null}
          clearable={!field.required}
          disabled={disabled}
          invalid={invalid}
          onBlur={onBlur}
          onChange={(next) => onChange(next ?? "")}
        />
      ) : (
        <Input {...common} value={asString(value)} onChange={(event) => onChange(event.target.value)} />
      );
      break;
    }
  }

  return (
    <div className={cn("flex min-w-0 flex-col gap-1.5", isWideField(field) && "col-span-full")}>
      <Label htmlFor={id} className="justify-between">
        <span className="flex items-center gap-1">
          {field.label}
          {field.required ? (
            <span className="text-destructive" aria-hidden>
              *
            </span>
          ) : null}
          {field.required ? <span className="sr-only">(required)</span> : null}
        </span>
        {disabled && lockedReason ? (
          <SimpleTooltip label={lockedReason}>
            <span className="text-subtle-foreground" aria-label={lockedReason}>
              <Lock className="size-3" />
            </span>
          </SimpleTooltip>
        ) : null}
      </Label>
      {control_}
      {fieldState.error ? (
        <p id={errorId} className="text-xs text-destructive" role="alert">
          {fieldState.error.message}
        </p>
      ) : field.description ? (
        <p id={descriptionId} className="text-xs text-muted-foreground">
          {field.description}
        </p>
      ) : null}
    </div>
  );
}
