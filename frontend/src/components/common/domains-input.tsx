"use client";

import * as React from "react";
import { X } from "lucide-react";

const DOMAIN = /^(?!-)[a-z0-9-]{1,63}(?:\.[a-z0-9-]{1,63})+$/;

/** Normalizes "@IIITT.ac.in " to "iiitt.ac.in". */
export function normalizeDomain(raw: string) {
  return raw.trim().toLowerCase().replace(/^@/, "");
}

export function DomainsInput({
  id,
  value,
  onChange,
  invalid,
  describedBy,
}: {
  id: string;
  value: string[];
  onChange: (domains: string[]) => void;
  invalid?: boolean;
  describedBy?: string;
}) {
  const [text, setText] = React.useState("");
  const [error, setError] = React.useState<string | null>(null);
  const commit = () => {
    const parts = text.split(/[\s,;]+/).map(normalizeDomain).filter(Boolean);
    if (parts.length === 0) return;
    const bad = parts.find((d) => !DOMAIN.test(d));
    if (bad) {
      setError(`“${bad}” isn't a domain like iiitt.ac.in`);
      return;
    }
    setError(null);
    onChange([...new Set([...value, ...parts])]);
    setText("");
  };
  return (
    <div className="grid gap-1.5">
      <div className="flex min-h-9 flex-wrap items-center gap-1 rounded-md border border-input bg-background px-1.5 py-1 shadow-xs focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/20">
        {value.map((domain) => (
          <span key={domain} className="inline-flex h-6 items-center gap-1 rounded-md border border-border bg-muted pl-1.5 pr-0.5 font-mono text-xs">
            @{domain}
            <button
              type="button"
              aria-label={`Remove ${domain}`}
              className="rounded-sm p-0.5 opacity-60 hover:opacity-100"
              onClick={() => onChange(value.filter((d) => d !== domain))}
            >
              <X className="size-3" />
            </button>
          </span>
        ))}
        <input
          id={id}
          value={text}
          placeholder={value.length ? "Add another" : "iiitt.ac.in"}
          aria-invalid={invalid || Boolean(error) || undefined}
          aria-describedby={describedBy}
          className="h-7 min-w-32 flex-1 bg-transparent px-1.5 text-sm outline-none placeholder:text-subtle-foreground"
          onChange={(event) => {
            setText(event.target.value);
            setError(null);
          }}
          onBlur={commit}
          onKeyDown={(event) => {
            if (event.key === "Enter" || event.key === "," || event.key === " ") {
              event.preventDefault();
              commit();
            } else if (event.key === "Backspace" && !text && value.length) {
              onChange(value.slice(0, -1));
            }
          }}
        />
      </div>
      {error ? <p className="text-xs text-destructive">{error}</p> : null}
    </div>
  );
}
