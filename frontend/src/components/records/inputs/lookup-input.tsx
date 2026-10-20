"use client";

import * as React from "react";

import { LookupValue } from "@/components/records/field-value";
import { Combobox, type ComboboxOption } from "@/components/ui/combobox";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useModule, useRecords } from "@/lib/api";
import { activeFields, displayField, recordTitle } from "@/lib/metadata/fields";
import { shortId } from "@/lib/metadata/format";

export interface LookupInputProps {
  id?: string;
  orgId: string;
  targetModuleKey: string;
  value: string | null;
  onChange: (value: string | null) => void;
  onBlur?: () => void;
  disabled?: boolean;
  invalid?: boolean;
  placeholder?: string;
  clearable?: boolean;
}

/** Async combobox over the target module, searching with `q` on its searchable fields. */
export function LookupInput({
  id,
  orgId,
  targetModuleKey,
  value,
  onChange,
  onBlur,
  disabled,
  invalid,
  placeholder,
  clearable = true,
}: LookupInputProps) {
  const [search, setSearch] = React.useState("");
  const q = useDebouncedValue(search, 250);
  const target = useModule(orgId, targetModuleKey);
  const records = useRecords(orgId, targetModuleKey, { q, size: 10 }, { enabled: !disabled });

  const options = React.useMemo<ComboboxOption[]>(() => {
    const def = target.data;
    const title = def ? displayField(def) : null;
    const secondary = def
      ? activeFields(def).find((f) => f.apiKey !== title?.apiKey && (f.type === "TEXT" || f.type === "EMAIL"))
      : undefined;
    return (records.data?.items ?? []).map((record) => {
      const extra = secondary ? record.data[secondary.apiKey] : undefined;
      return {
        value: record.id,
        label: recordTitle(def, record),
        description: typeof extra === "string" ? extra : shortId(record.id),
      };
    });
  }, [records.data, target.data]);

  const label = target.data?.labelSingular.toLowerCase() ?? "record";

  return (
    <Combobox
      id={id}
      value={value}
      onChange={onChange}
      onBlur={onBlur}
      options={options}
      disabled={disabled}
      invalid={invalid}
      clearable={clearable}
      loading={records.isFetching && !records.data}
      onSearchChange={setSearch}
      placeholder={placeholder ?? `Select ${label}…`}
      searchPlaceholder={`Search ${target.data?.labelPlural.toLowerCase() ?? "records"}…`}
      emptyText={records.isError ? "Couldn't load records" : q ? `No ${label} matches “${q}”` : `No ${label} yet`}
      selectedLabel={value ? <LookupValue orgId={orgId} targetModuleKey={targetModuleKey} id={value} link={false} /> : null}
    />
  );
}
