"use client";

import * as React from "react";
import { WandSparkles } from "lucide-react";

import { Button } from "@/components/ui/button";
import { DatePicker } from "@/components/ui/date-picker";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { DEFAULT_CURRENCIES, type CurrencyFormValue } from "@/lib/metadata/form";
import { cn } from "@/lib/utils";

const CURRENCY_SYMBOLS: Record<string, string> = { INR: "₹", USD: "$", EUR: "€", GBP: "£" };

export function CurrencyInput({
  id,
  value,
  onChange,
  onBlur,
  currencies,
  disabled,
  invalid,
}: {
  id?: string;
  value: CurrencyFormValue;
  onChange: (value: CurrencyFormValue) => void;
  onBlur?: () => void;
  currencies?: string[];
  disabled?: boolean;
  invalid?: boolean;
}) {
  const choices = currencies?.length ? currencies : DEFAULT_CURRENCIES;
  const list = choices.includes(value.currency) ? choices : [value.currency, ...choices];
  return (
    <div className="flex">
      <Select
        value={value.currency}
        onValueChange={(currency) => onChange({ ...value, currency })}
        disabled={disabled || list.length === 1}
      >
        <SelectTrigger aria-label="Currency" className="w-[5.5rem] rounded-r-none border-r-0 focus-visible:z-10">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {list.map((code) => (
            <SelectItem key={code} value={code}>
              {code}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <div className="relative flex-1">
        {CURRENCY_SYMBOLS[value.currency] ? (
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">
            {CURRENCY_SYMBOLS[value.currency]}
          </span>
        ) : null}
        <Input
          id={id}
          inputMode="decimal"
          autoComplete="off"
          placeholder="0"
          value={value.amount}
          disabled={disabled}
          aria-invalid={invalid || undefined}
          onBlur={onBlur}
          onChange={(event) => onChange({ ...value, amount: event.target.value.replace(/[,\s]/g, "") })}
          className={cn("rounded-l-none tabular", CURRENCY_SYMBOLS[value.currency] && "pl-7")}
        />
      </div>
    </div>
  );
}

/** Local date + time (value `yyyy-MM-ddTHH:mm`, converted to UTC on submit). */
export function DateTimeInput({
  id,
  value,
  onChange,
  onBlur,
  disabled,
  invalid,
  clearable = true,
}: {
  id?: string;
  value: string;
  onChange: (value: string) => void;
  onBlur?: () => void;
  disabled?: boolean;
  invalid?: boolean;
  clearable?: boolean;
}) {
  const [date, time] = value ? value.split("T") : ["", ""];
  return (
    <div className="flex gap-2">
      <DatePicker
        id={id}
        className="flex-1"
        value={date || null}
        disabled={disabled}
        invalid={invalid}
        clearable={clearable}
        onBlur={onBlur}
        onChange={(next) => onChange(next ? `${next}T${time || "09:00"}` : "")}
      />
      <Input
        type="time"
        aria-label="Time"
        className="w-28 tabular"
        value={time ?? ""}
        disabled={disabled || !date}
        aria-invalid={invalid || undefined}
        onBlur={onBlur}
        onChange={(event) => date && onChange(`${date}T${event.target.value || "00:00"}`)}
      />
    </div>
  );
}

export function JsonInput({
  id,
  value,
  onChange,
  onBlur,
  disabled,
  invalid,
}: {
  id?: string;
  value: string;
  onChange: (value: string) => void;
  onBlur?: () => void;
  disabled?: boolean;
  invalid?: boolean;
}) {
  const format = () => {
    try {
      onChange(JSON.stringify(JSON.parse(value), null, 2));
    } catch {
      /* invalid JSON is reported by validation */
    }
  };
  return (
    <div className="relative">
      <Textarea
        id={id}
        value={value}
        spellCheck={false}
        disabled={disabled}
        aria-invalid={invalid || undefined}
        onBlur={onBlur}
        onChange={(event) => onChange(event.target.value)}
        placeholder='{ "key": "value" }'
        className="min-h-32 font-mono text-xs leading-relaxed"
      />
      {!disabled && value.trim() ? (
        <Button variant="outline" size="xs" className="absolute right-2 top-2 bg-background" onClick={format}>
          <WandSparkles /> Format
        </Button>
      ) : null}
    </div>
  );
}
