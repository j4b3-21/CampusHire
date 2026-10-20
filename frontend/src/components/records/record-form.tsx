"use client";

import * as React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { toast } from "sonner";
import { AlertTriangle, RotateCw } from "lucide-react";

import { FieldInput } from "@/components/records/field-input";
import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/kbd";
import { Spinner } from "@/components/ui/spinner";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ApiError, useCreateRecord, usePatchRecord, type VersionedRecord } from "@/lib/api";
import { isFieldEditable, visibleFields } from "@/lib/metadata/fields";
import {
  buildRecordSchema,
  createPayload,
  initialFormValues,
  patchPayload,
  type RecordFormValues,
} from "@/lib/metadata/form";
import { gridColumnsClass, resolveLayout, type ResolvedSection } from "@/lib/metadata/layout";
import { cn } from "@/lib/utils";
import type { FieldDefinition, ModuleDefinition } from "@/types/metadata";
import type { ModuleCapabilities } from "@/types/org";

export interface RecordFormProps {
  orgId: string;
  module: ModuleDefinition;
  capabilities: ModuleCapabilities;
  /** Present in edit mode. */
  record?: VersionedRecord;
  onSaved: (record: VersionedRecord) => void;
  onCancel: () => void;
  /** Reload the record after a version conflict. */
  onReload?: () => Promise<unknown>;
}

function lockReason(field: FieldDefinition, capabilities: ModuleCapabilities) {
  if (field.readOnly) return "This field is set by the system";
  if (field.status === "DEPRECATED") return "This field is no longer used";
  if (capabilities.readOnlyFields.includes(field.apiKey)) return "Your role can't change this field";
  return undefined;
}

export function RecordForm({ orgId, module, capabilities, record, onSaved, onCancel, onReload }: RecordFormProps) {
  const mode = record ? "edit" : "create";
  const fields = React.useMemo(() => visibleFields(module, capabilities), [module, capabilities]);
  const editable = React.useMemo(
    () => fields.filter((field) => isFieldEditable(field, capabilities)),
    [fields, capabilities]
  );
  const tabs = React.useMemo(() => resolveLayout(module, fields), [module, fields]);
  const schema = React.useMemo(() => buildRecordSchema(editable), [editable]);
  const initial = React.useMemo(() => initialFormValues(fields, record?.data), [fields, record?.data]);

  const form = useForm<RecordFormValues>({
    resolver: zodResolver(schema),
    defaultValues: initial,
    mode: "onTouched",
  });
  const [conflict, setConflict] = React.useState(false);
  const [baseRecord, setBaseRecord] = React.useState(record);
  const [activeTab, setActiveTab] = React.useState(tabs[0]?.key);

  const create = useCreateRecord(orgId, module.apiKey);
  const patch = usePatchRecord(orgId, module.apiKey);
  const saving = create.isPending || patch.isPending;

  const tabOfField = React.useCallback(
    (key: string) => tabs.find((tab) => tab.sections.some((s) => s.fields.some((f) => f.apiKey === key)))?.key,
    [tabs]
  );

  const applyServerErrors = (error: ApiError) => {
    const fieldErrors = error.fieldErrors();
    const known = new Set(fields.map((field) => field.apiKey));
    const unknown: string[] = [];
    let first: string | undefined;
    for (const [key, message] of Object.entries(fieldErrors)) {
      if (known.has(key)) {
        form.setError(key, { type: "server", message });
        first ??= key;
      } else {
        unknown.push(message);
      }
    }
    if (first) {
      const tab = tabOfField(first);
      if (tab) setActiveTab(tab);
      setTimeout(() => form.setFocus(first!), 0);
    }
    toast.error(error.friendlyMessage, { description: unknown.join(" ") || undefined });
  };

  const onSubmit = form.handleSubmit(
    async (values) => {
      setConflict(false);
      try {
        if (mode === "create") {
          const saved = await create.mutateAsync(createPayload(editable, values));
          toast.success(`${module.labelSingular} created`);
          onSaved(saved);
        } else if (baseRecord) {
          const data = patchPayload(editable, values, initialFormValues(fields, baseRecord.data));
          if (Object.keys(data).length === 0) {
            toast("No changes to save");
            onSaved(baseRecord);
            return;
          }
          const saved = await patch.mutateAsync({ id: baseRecord.id, ifMatch: baseRecord.etag, data });
          toast.success("Changes saved");
          onSaved(saved);
        }
      } catch (error) {
        if (!(error instanceof ApiError)) return;
        if (error.isValidation) applyServerErrors(error);
        else if (error.isVersionConflict) setConflict(true);
      }
    },
    (errors) => {
      const first = Object.keys(errors)[0];
      const tab = first ? tabOfField(first) : undefined;
      if (tab && tab !== activeTab) setActiveTab(tab);
    }
  );

  const reloadLatest = async () => {
    const latest = (await onReload?.()) as VersionedRecord | undefined;
    if (latest) {
      setBaseRecord(latest);
      form.reset(initialFormValues(fields, latest.data), { keepDirtyValues: true });
      setConflict(false);
      toast("Loaded the latest version", { description: "Your unsaved edits were kept. Review them and save again." });
    }
  };

  const renderSection = (section: ResolvedSection, showTitle: boolean) => (
    <section key={section.key} aria-labelledby={showTitle ? `section-${section.key}` : undefined} className="flex flex-col gap-4">
      {showTitle ? (
        <h3 id={`section-${section.key}`} className="text-[13px] font-semibold text-foreground">
          {section.label}
        </h3>
      ) : null}
      <div className={cn("grid grid-cols-1 gap-x-5 gap-y-5", gridColumnsClass(section.columns))}>
        {section.fields.map((field) => {
          const reason = lockReason(field, capabilities);
          return (
            <FieldInput
              key={field.apiKey}
              orgId={orgId}
              field={field}
              control={form.control}
              disabled={Boolean(reason) || saving}
              lockedReason={reason}
            />
          );
        })}
      </div>
    </section>
  );

  const body =
    tabs.length > 1 ? (
      <Tabs value={activeTab} onValueChange={setActiveTab}>
        <TabsList className="mb-6">
          {tabs.map((tab) => {
            const hasErrors = tab.sections.some((s) => s.fields.some((f) => form.formState.errors[f.apiKey]));
            return (
              <TabsTrigger key={tab.key} value={tab.key}>
                {tab.label}
                {hasErrors ? (
                  <>
                    <span className="size-1.5 rounded-full bg-destructive" aria-hidden />
                    <span className="sr-only">(has errors)</span>
                  </>
                ) : null}
              </TabsTrigger>
            );
          })}
        </TabsList>
        {tabs.map((tab) => (
          <TabsContent key={tab.key} value={tab.key} forceMount className="flex flex-col gap-8 data-[state=inactive]:hidden">
            {tab.sections.map((section) => renderSection(section, tab.sections.length > 1 || section.label !== tab.label))}
          </TabsContent>
        ))}
      </Tabs>
    ) : (
      <div className="flex flex-col gap-8">
        {(tabs[0]?.sections ?? []).map((section, _i, all) => renderSection(section, all.length > 1))}
      </div>
    );

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      onKeyDown={(event) => {
        if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
          event.preventDefault();
          void onSubmit();
        }
      }}
      className="flex flex-col"
    >
      {conflict ? (
        <div
          role="alert"
          className="mb-6 flex flex-wrap items-center gap-3 rounded-lg border border-warning/30 bg-warning-soft px-4 py-3 text-[13px] text-warning-soft-foreground"
        >
          <AlertTriangle className="size-4 shrink-0" />
          <p className="flex-1">Someone else saved this record while you were editing.</p>
          {onReload ? (
            <Button variant="outline" size="sm" onClick={() => void reloadLatest()}>
              <RotateCw /> Load latest and keep my edits
            </Button>
          ) : null}
        </div>
      ) : null}

      {fields.length === 0 ? (
        <p className="rounded-lg border border-dashed border-border-strong px-4 py-8 text-center text-[13px] text-muted-foreground">
          This module has no fields you can edit.
        </p>
      ) : (
        body
      )}

      <div className="sticky bottom-0 -mx-4 mt-10 flex items-center justify-end gap-2 border-t border-border bg-background/90 px-4 py-3 backdrop-blur sm:-mx-6 sm:px-6 lg:-mx-8 lg:px-8">
        <span className="mr-auto hidden items-center gap-1 text-xs text-muted-foreground sm:flex">
          <Kbd>⌘</Kbd>
          <Kbd>Enter</Kbd> to save
        </span>
        <Button variant="outline" onClick={onCancel} disabled={saving}>
          Cancel
        </Button>
        <Button type="submit" disabled={saving || editable.length === 0}>
          {saving ? <Spinner className="text-primary-foreground" /> : null}
          {mode === "create" ? `Create ${module.labelSingular.toLowerCase()}` : "Save changes"}
        </Button>
      </div>
    </form>
  );
}
