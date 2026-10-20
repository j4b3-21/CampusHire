"use client";

import * as React from "react";
import { Check, ChevronsUpDown, X } from "lucide-react";

import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from "@/components/ui/command";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { optionLabel } from "@/lib/metadata/fields";
import { TONE_CLASSES, optionTone } from "@/lib/metadata/tones";
import { cn } from "@/lib/utils";
import type { FieldDefinition } from "@/types/metadata";

export interface MultiSelectInputProps {
  id?: string;
  field: FieldDefinition;
  value: string[];
  onChange: (value: string[]) => void;
  onBlur?: () => void;
  disabled?: boolean;
  invalid?: boolean;
}

export function MultiSelectInput({ id, field, value, onChange, onBlur, disabled, invalid }: MultiSelectInputProps) {
  const [open, setOpen] = React.useState(false);
  const listId = React.useId();
  const options = (field.config?.options ?? []).filter((option) => !option.deprecated || value.includes(option.value));
  const max = field.config?.maxSelections;
  const atLimit = max !== undefined && value.length >= max;

  const toggle = (optionValue: string) => {
    if (value.includes(optionValue)) onChange(value.filter((v) => v !== optionValue));
    else if (!atLimit) onChange([...value, optionValue]);
  };

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) onBlur?.();
      }}
    >
      <PopoverTrigger asChild>
        <div
          id={id}
          role="combobox"
          tabIndex={disabled ? -1 : 0}
          aria-expanded={open}
          aria-controls={listId}
          aria-disabled={disabled || undefined}
          aria-invalid={invalid || undefined}
          onKeyDown={(event) => {
            if (event.key === "Enter" || event.key === " " || event.key === "ArrowDown") {
              event.preventDefault();
              if (!disabled) setOpen(true);
            }
          }}
          className={cn(
            "focus-ring flex min-h-9 w-full cursor-pointer flex-wrap items-center gap-1 rounded-md border border-input bg-background py-1 pl-1.5 pr-8 text-sm shadow-xs transition-[border-color] hover:border-border-strong aria-disabled:cursor-not-allowed aria-disabled:bg-muted aria-disabled:opacity-80 aria-invalid:border-destructive",
            "relative"
          )}
        >
          {value.length === 0 ? <span className="px-1.5 text-subtle-foreground">Select…</span> : null}
          {value.map((item) => (
            <span
              key={item}
              className={cn(
                "inline-flex h-6 items-center gap-1 rounded-md border pl-1.5 pr-0.5 text-xs font-medium",
                TONE_CLASSES[optionTone(field, item)]
              )}
            >
              {optionLabel(field, item)}
              {!disabled ? (
                <button
                  type="button"
                  aria-label={`Remove ${optionLabel(field, item)}`}
                  className="rounded-sm p-0.5 opacity-60 hover:bg-black/5 hover:opacity-100 dark:hover:bg-white/10"
                  onPointerDown={(event) => event.stopPropagation()}
                  onClick={(event) => {
                    event.stopPropagation();
                    toggle(item);
                  }}
                >
                  <X className="size-3" />
                </button>
              ) : null}
            </span>
          ))}
          <ChevronsUpDown className="pointer-events-none absolute right-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
        </div>
      </PopoverTrigger>
      <PopoverContent id={listId} className="w-(--radix-popover-trigger-width) min-w-56 p-0" align="start">
        <Command>
          {options.length > 7 ? <CommandInput placeholder="Search options…" /> : null}
          <CommandList>
            <CommandEmpty>No options</CommandEmpty>
            <CommandGroup>
              {options.map((option) => {
                const selected = value.includes(option.value);
                return (
                  <CommandItem
                    key={option.value}
                    value={`${option.label} ${option.value}`}
                    disabled={!selected && atLimit}
                    onSelect={() => toggle(option.value)}
                  >
                    <span
                      className={cn(
                        "grid size-4 place-items-center rounded-[4px] border",
                        selected ? "border-primary bg-primary text-primary-foreground" : "border-border-strong"
                      )}
                    >
                      {selected ? <Check className="size-3 !text-primary-foreground" strokeWidth={3} /> : null}
                    </span>
                    {option.label}
                  </CommandItem>
                );
              })}
            </CommandGroup>
          </CommandList>
          {max !== undefined ? (
            <div className="border-t border-border px-3 py-2 text-xs text-muted-foreground">
              {value.length} of {max} selected
            </div>
          ) : null}
        </Command>
      </PopoverContent>
    </Popover>
  );
}
