"use client";

import * as React from "react";
import { Check, ChevronsUpDown, Loader2, X } from "lucide-react";

import { cn } from "@/lib/utils";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from "@/components/ui/command";

export interface ComboboxOption {
  value: string;
  label: string;
  description?: string;
  disabled?: boolean;
}

export interface ComboboxProps {
  id?: string;
  value?: string | null;
  onChange: (value: string | null) => void;
  options: ComboboxOption[];
  placeholder?: string;
  searchPlaceholder?: string;
  emptyText?: string;
  disabled?: boolean;
  invalid?: boolean;
  clearable?: boolean;
  loading?: boolean;
  className?: string;
  /** Label shown for the current value when it is not part of `options` (e.g. async results). */
  selectedLabel?: React.ReactNode;
  /** When provided, filtering is delegated to the caller (server-side search). */
  onSearchChange?: (search: string) => void;
  onBlur?: () => void;
}

export function Combobox({
  id,
  value,
  onChange,
  options,
  placeholder = "Select…",
  searchPlaceholder = "Search…",
  emptyText = "No results",
  disabled,
  invalid,
  clearable = true,
  loading,
  className,
  selectedLabel,
  onSearchChange,
  onBlur,
}: ComboboxProps) {
  const [open, setOpen] = React.useState(false);
  const [search, setSearch] = React.useState("");
  const listId = React.useId();
  const current = options.find((option) => option.value === value);
  const label = current?.label ?? (value ? selectedLabel : null);

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          setSearch("");
          onSearchChange?.("");
          onBlur?.();
        }
      }}
    >
      <div className={cn("relative", className)}>
        <PopoverTrigger asChild>
          <button
            id={id}
            type="button"
            role="combobox"
            aria-expanded={open}
            aria-controls={listId}
            aria-invalid={invalid || undefined}
            disabled={disabled}
            className={cn(
              "flex h-9 w-full items-center gap-2 rounded-md border border-input bg-background pl-3 pr-8 text-left text-sm shadow-xs transition-[border-color,box-shadow] hover:border-border-strong focus-visible:border-ring focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/20 disabled:cursor-not-allowed disabled:bg-muted disabled:opacity-80 aria-invalid:border-destructive",
              !label && "text-subtle-foreground"
            )}
          >
            <span className="min-w-0 flex-1 truncate">{label ?? placeholder}</span>
          </button>
        </PopoverTrigger>
        {clearable && value && !disabled ? (
          <button
            type="button"
            aria-label="Clear selection"
            onClick={() => onChange(null)}
            className="focus-ring absolute right-2 top-1/2 -translate-y-1/2 rounded-sm p-0.5 text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <X className="size-3.5" />
          </button>
        ) : (
          <ChevronsUpDown className="pointer-events-none absolute right-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
        )}
      </div>
      <PopoverContent id={listId} className="w-(--radix-popover-trigger-width) min-w-56 p-0" align="start">
        <Command shouldFilter={!onSearchChange}>
          <CommandInput
            placeholder={searchPlaceholder}
            value={search}
            onValueChange={(next) => {
              setSearch(next);
              onSearchChange?.(next);
            }}
          />
          <CommandList>
            {loading ? (
              <div className="flex items-center justify-center gap-2 py-6 text-[13px] text-muted-foreground">
                <Loader2 className="size-4 animate-spin" /> Searching…
              </div>
            ) : (
              <CommandEmpty>{emptyText}</CommandEmpty>
            )}
            {!loading && options.length > 0 ? (
              <CommandGroup>
                {options.map((option) => (
                  <CommandItem
                    key={option.value}
                    value={`${option.label} ${option.value}`}
                    disabled={option.disabled}
                    onSelect={() => {
                      onChange(option.value === value ? null : option.value);
                      setOpen(false);
                    }}
                  >
                    <Check className={cn("!text-foreground", option.value === value ? "opacity-100" : "opacity-0")} />
                    <div className="min-w-0 flex-1">
                      <div className="truncate">{option.label}</div>
                      {option.description ? (
                        <div className="truncate text-xs text-muted-foreground">{option.description}</div>
                      ) : null}
                    </div>
                  </CommandItem>
                ))}
              </CommandGroup>
            ) : null}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
