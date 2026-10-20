"use client";

import * as React from "react";
import { Check, ChevronsUpDown, X } from "lucide-react";

import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from "@/components/ui/command";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";

export interface MultiComboboxOption {
  value: string;
  label: string;
  description?: string;
  /** Rendered instead of the label inside the chip and the list. */
  render?: React.ReactNode;
}

export interface MultiComboboxProps {
  id?: string;
  value: string[];
  onChange: (value: string[]) => void;
  options: MultiComboboxOption[];
  placeholder?: string;
  searchPlaceholder?: string;
  emptyText?: string;
  disabled?: boolean;
  invalid?: boolean;
  className?: string;
  "aria-label"?: string;
  "aria-describedby"?: string;
}

/** Generic multi-select with chips (not bound to a field definition). */
export function MultiCombobox({
  id,
  value,
  onChange,
  options,
  placeholder = "Select…",
  searchPlaceholder = "Search…",
  emptyText = "No options",
  disabled,
  invalid,
  className,
  ...aria
}: MultiComboboxProps) {
  const [open, setOpen] = React.useState(false);
  const listId = React.useId();
  const byValue = new Map(options.map((option) => [option.value, option]));
  const toggle = (item: string) => onChange(value.includes(item) ? value.filter((v) => v !== item) : [...value, item]);

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <div
          id={id}
          role="combobox"
          tabIndex={disabled ? -1 : 0}
          aria-expanded={open}
          aria-controls={listId}
          aria-disabled={disabled || undefined}
          aria-invalid={invalid || undefined}
          aria-label={aria["aria-label"]}
          aria-describedby={aria["aria-describedby"]}
          onKeyDown={(event) => {
            if (event.key === "Enter" || event.key === " " || event.key === "ArrowDown") {
              event.preventDefault();
              if (!disabled) setOpen(true);
            }
          }}
          className={cn(
            "focus-ring relative flex min-h-9 w-full cursor-pointer flex-wrap items-center gap-1 rounded-md border border-input bg-background py-1 pl-1.5 pr-8 text-sm shadow-xs transition-[border-color] hover:border-border-strong aria-disabled:cursor-not-allowed aria-disabled:bg-muted aria-disabled:opacity-80 aria-invalid:border-destructive",
            className
          )}
        >
          {value.length === 0 ? <span className="px-1.5 text-subtle-foreground">{placeholder}</span> : null}
          {value.map((item) => {
            const option = byValue.get(item);
            return (
              <span
                key={item}
                className="inline-flex h-6 max-w-full items-center gap-1 rounded-md border border-border bg-muted pl-1.5 pr-0.5 text-xs font-medium"
              >
                <span className="truncate">{option?.render ?? option?.label ?? item}</span>
                {!disabled ? (
                  <button
                    type="button"
                    aria-label={`Remove ${option?.label ?? item}`}
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
            );
          })}
          <ChevronsUpDown className="pointer-events-none absolute right-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
        </div>
      </PopoverTrigger>
      <PopoverContent id={listId} className="w-(--radix-popover-trigger-width) min-w-56 p-0" align="start">
        <Command>
          {options.length > 7 ? <CommandInput placeholder={searchPlaceholder} /> : null}
          <CommandList>
            <CommandEmpty>{emptyText}</CommandEmpty>
            <CommandGroup>
              {options.map((option) => {
                const selected = value.includes(option.value);
                return (
                  <CommandItem key={option.value} value={`${option.label} ${option.value}`} onSelect={() => toggle(option.value)}>
                    <span
                      className={cn(
                        "grid size-4 shrink-0 place-items-center rounded-[4px] border",
                        selected ? "border-primary bg-primary text-primary-foreground" : "border-border-strong"
                      )}
                    >
                      {selected ? <Check className="size-3 !text-primary-foreground" strokeWidth={3} /> : null}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="truncate">{option.render ?? option.label}</div>
                      {option.description ? <div className="truncate text-xs text-muted-foreground">{option.description}</div> : null}
                    </div>
                  </CommandItem>
                );
              })}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
