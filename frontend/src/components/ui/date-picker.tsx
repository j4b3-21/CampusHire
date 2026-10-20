"use client";

import * as React from "react";
import { format, isValid, parse } from "date-fns";
import { CalendarDays, X } from "lucide-react";

import { cn } from "@/lib/utils";
import { Calendar } from "@/components/ui/calendar";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

const ISO_DATE = "yyyy-MM-dd";

export function parseIsoDate(value?: string | null) {
  if (!value) return undefined;
  const date = parse(value.slice(0, 10), ISO_DATE, new Date());
  return isValid(date) ? date : undefined;
}

export interface DatePickerProps {
  id?: string;
  value?: string | null;
  onChange: (value: string | null) => void;
  placeholder?: string;
  disabled?: boolean;
  invalid?: boolean;
  className?: string;
  clearable?: boolean;
  onBlur?: () => void;
}

/** Date picker whose value is an ISO local date string (`2026-10-20`). */
export function DatePicker({
  id,
  value,
  onChange,
  placeholder = "Pick a date",
  disabled,
  invalid,
  className,
  clearable = true,
  onBlur,
}: DatePickerProps) {
  const [open, setOpen] = React.useState(false);
  const selected = parseIsoDate(value);

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) onBlur?.();
      }}
    >
      <div className={cn("relative", className)}>
        <PopoverTrigger asChild>
          <button
            id={id}
            type="button"
            disabled={disabled}
            data-invalid={invalid || undefined}
            className={cn(
              "flex h-9 w-full items-center gap-2 rounded-md border border-input bg-background px-3 text-left text-sm shadow-xs transition-[border-color,box-shadow] hover:border-border-strong focus-visible:border-ring focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/20 disabled:cursor-not-allowed disabled:bg-muted disabled:opacity-80 data-[invalid=true]:border-destructive",
              !selected && "text-subtle-foreground"
            )}
          >
            <CalendarDays className="size-4 shrink-0 text-muted-foreground" />
            <span className="truncate tabular">{selected ? format(selected, "d MMM yyyy") : placeholder}</span>
          </button>
        </PopoverTrigger>
        {clearable && selected && !disabled ? (
          <button
            type="button"
            aria-label="Clear date"
            onClick={() => onChange(null)}
            className="focus-ring absolute right-2 top-1/2 -translate-y-1/2 rounded-sm p-0.5 text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <X className="size-3.5" />
          </button>
        ) : null}
      </div>
      <PopoverContent className="w-auto p-2" align="start">
        <Calendar
          mode="single"
          selected={selected}
          defaultMonth={selected}
          startMonth={new Date(1950, 0)}
          endMonth={new Date(new Date().getFullYear() + 10, 11)}
          onSelect={(date) => {
            onChange(date ? format(date, ISO_DATE) : null);
            setOpen(false);
          }}
          autoFocus
        />
      </PopoverContent>
    </Popover>
  );
}
