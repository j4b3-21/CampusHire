"use client";

import * as React from "react";
import {
  closestCorners,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
  type UniqueIdentifier,
} from "@dnd-kit/core";
import { arrayMove, rectSortingStrategy, SortableContext, sortableKeyboardCoordinates, useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { toast } from "sonner";
import { ArrowDown, ArrowUp, GripVertical, LayoutTemplate, Plus, RotateCcw, Save, Trash2 } from "lucide-react";

import { FieldMessage, toApiKey } from "@/components/admin/admin-gate";
import { FIELD_TYPE_LABELS } from "@/components/admin/field-dialog";
import { useOrg } from "@/components/app/org-context";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { ApiError, useSaveLayout } from "@/lib/api";
import { violationMap, violationsUnder } from "@/lib/api/violations";
import { activeFields } from "@/lib/metadata/fields";
import { isWideField } from "@/lib/metadata/layout";
import { cn } from "@/lib/utils";
import type { FieldDefinition, LayoutSection, ModuleDefinition, ModuleLayout } from "@/types/metadata";

const POOL = "__pool";

interface SectionDraft {
  id: string;
  key: string;
  keyEdited: boolean;
  label: string;
  tab: string;
  columns: number;
  fields: string[];
}

let sectionUid = 0;
const nextId = () => `section-${++sectionUid}`;

function initialSections(module: ModuleDefinition): SectionDraft[] {
  const fields = activeFields(module);
  const known = new Set(fields.map((f) => f.apiKey));
  const sections = module.layout?.sections ?? [];
  if (sections.length === 0) {
    return [{ id: nextId(), key: "details", keyEdited: true, label: "Details", tab: "", columns: 2, fields: fields.map((f) => f.apiKey) }];
  }
  const placed = new Set<string>();
  return sections.map((section) => ({
    id: nextId(),
    key: section.key,
    keyEdited: true,
    label: section.label,
    tab: section.tab ?? "",
    columns: Math.min(Math.max(section.columns ?? 1, 1), 4),
    fields: section.fields.filter((key) => {
      if (!known.has(key) || placed.has(key)) return false;
      placed.add(key);
      return true;
    }),
  }));
}

function toLayout(sections: SectionDraft[]): ModuleLayout {
  return {
    sections: sections.map(
      (s): LayoutSection => ({
        key: s.key,
        label: s.label.trim(),
        ...(s.tab.trim() ? { tab: s.tab.trim() } : {}),
        columns: s.columns,
        fields: s.fields,
      })
    ),
  };
}

function FieldChip({ field, overlay, columns }: { field: FieldDefinition; overlay?: boolean; columns: number }) {
  return (
    <div
      className={cn(
        "flex h-10 min-w-0 items-center gap-2 rounded-md border border-border bg-background px-2 text-[13px] shadow-xs",
        overlay && "border-primary/50 shadow-lg",
        field.status === "DEPRECATED" && "opacity-60"
      )}
    >
      <GripVertical className="size-3.5 shrink-0 text-subtle-foreground" />
      <span className="min-w-0 flex-1 truncate font-medium">{field.label}</span>
      {field.required ? <span className="text-destructive" aria-label="required">*</span> : null}
      <span className="hidden shrink-0 text-[11px] text-subtle-foreground sm:inline">{FIELD_TYPE_LABELS[field.type]}</span>
      {isWideField(field) && columns > 1 ? <Badge variant="outline" className="shrink-0 px-1 text-[10px]">full width</Badge> : null}
    </div>
  );
}

function SortableField({ field, columns }: { field: FieldDefinition; columns: number }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: field.apiKey });
  return (
    <li
      ref={setNodeRef}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={cn("focus-ring cursor-grab rounded-md active:cursor-grabbing", isWideField(field) && "col-span-full", isDragging && "opacity-30")}
      {...attributes}
      {...listeners}
      aria-label={`${field.label}, ${FIELD_TYPE_LABELS[field.type]}`}
    >
      <FieldChip field={field} columns={columns} />
    </li>
  );
}

function FieldContainer({
  id,
  fields,
  columns,
  empty,
}: {
  id: string;
  fields: FieldDefinition[];
  columns: number;
  empty: string;
}) {
  const { setNodeRef, isOver } = useDroppable({ id });
  return (
    <SortableContext id={id} items={fields.map((f) => f.apiKey)} strategy={rectSortingStrategy}>
      <ul
        ref={setNodeRef}
        className={cn("grid min-h-14 gap-2 rounded-lg border border-dashed border-transparent p-1 transition-colors", isOver && "border-primary/50 bg-primary-soft/30")}
        style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
      >
        {fields.map((field) => (
          <SortableField key={field.apiKey} field={field} columns={columns} />
        ))}
        {fields.length === 0 ? (
          <li className="col-span-full grid h-12 place-items-center rounded-md border border-dashed border-border text-xs text-subtle-foreground">{empty}</li>
        ) : null}
      </ul>
    </SortableContext>
  );
}

export function LayoutBuilder({ module }: { module: ModuleDefinition }) {
  const { orgId } = useOrg();
  const save = useSaveLayout(orgId, module.apiKey);
  const fields = React.useMemo(() => activeFields(module), [module]);
  const byKey = React.useMemo(() => new Map(fields.map((f) => [f.apiKey, f])), [fields]);
  const [original] = React.useState(() => initialSections(module));
  const [sections, setSections] = React.useState<SectionDraft[]>(original);
  const [activeId, setActiveId] = React.useState<string | null>(null);
  const [errors, setErrors] = React.useState<Map<string, string[]>>(new Map());
  const tabsListId = React.useId();

  const placed = new Set(sections.flatMap((s) => s.fields));
  const pool = fields.filter((f) => !placed.has(f.apiKey));
  const dirty = JSON.stringify(toLayout(sections)) !== JSON.stringify(toLayout(original));
  const tabNames = [...new Set(sections.map((s) => s.tab.trim()).filter(Boolean))];

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const containerOf = (id: UniqueIdentifier): string | null => {
    const key = String(id);
    if (key === POOL || sections.some((s) => s.id === key)) return key;
    if (pool.some((f) => f.apiKey === key)) return POOL;
    return sections.find((s) => s.fields.includes(key))?.id ?? null;
  };

  const onDragStart = ({ active }: DragStartEvent) => setActiveId(String(active.id));

  const onDragOver = ({ active, over }: DragOverEvent) => {
    if (!over) return;
    const from = containerOf(active.id);
    const to = containerOf(over.id);
    if (!from || !to || from === to) return;
    const fieldKey = String(active.id);
    setSections((current) =>
      current.map((section) => {
        if (section.id === from) return { ...section, fields: section.fields.filter((f) => f !== fieldKey) };
        if (section.id === to) {
          const without = section.fields.filter((f) => f !== fieldKey);
          const overIndex = without.indexOf(String(over.id));
          const index = overIndex >= 0 ? overIndex : without.length;
          return { ...section, fields: [...without.slice(0, index), fieldKey, ...without.slice(index)] };
        }
        return section;
      })
    );
  };

  const onDragEnd = ({ active, over }: DragEndEvent) => {
    setActiveId(null);
    if (!over) return;
    const container = containerOf(active.id);
    const overContainer = containerOf(over.id);
    if (!container || container !== overContainer || container === POOL) return;
    setSections((current) =>
      current.map((section) => {
        if (section.id !== container) return section;
        const oldIndex = section.fields.indexOf(String(active.id));
        const newIndex = section.fields.indexOf(String(over.id));
        if (oldIndex < 0 || newIndex < 0 || oldIndex === newIndex) return section;
        return { ...section, fields: arrayMove(section.fields, oldIndex, newIndex) };
      })
    );
  };

  const patchSection = (id: string, patch: Partial<SectionDraft>) =>
    setSections((current) =>
      current.map((s) => {
        if (s.id !== id) return s;
        const next = { ...s, ...patch };
        if (patch.label !== undefined && !s.keyEdited) next.key = toApiKey(patch.label) || s.key;
        return next;
      })
    );
  const moveSection = (index: number, delta: number) =>
    setSections((current) => {
      const target = index + delta;
      if (target < 0 || target >= current.length) return current;
      return arrayMove(current, index, target);
    });
  const addSection = () =>
    setSections((current) => [
      ...current,
      { id: nextId(), key: `section_${current.length + 1}`, keyEdited: false, label: `Section ${current.length + 1}`, tab: "", columns: 2, fields: [] },
    ]);

  const onSave = async () => {
    const local = new Map<string, string[]>();
    const keys = new Set<string>();
    sections.forEach((s, i) => {
      if (!/^[a-z][a-z0-9_]{0,62}$/.test(s.key)) local.set(`layout.sections[${i}].key`, ["Section key must be lowercase letters, digits and underscores"]);
      else if (keys.has(s.key)) local.set(`layout.sections[${i}].key`, ["Two sections use the same key"]);
      keys.add(s.key);
      if (!s.label.trim()) local.set(`layout.sections[${i}].label`, ["Give the section a name"]);
    });
    setErrors(local);
    if (local.size) return;
    try {
      await save.mutateAsync({ version: module.version, layout: toLayout(sections) });
      toast.success("Layout saved", { description: pool.length ? `${pool.length} unplaced fields show under “Other fields”.` : undefined });
    } catch (error) {
      if (error instanceof ApiError && error.isValidation) setErrors(violationMap(error));
      else if (error instanceof ApiError && error.isVersionConflict) {
        toast.error("The module changed in the meantime", { description: "Reload the page and arrange the layout again." });
      }
    }
  };

  const activeField = activeId ? byKey.get(activeId) : undefined;
  const generalErrors = [...(errors.get("layout") ?? []), ...(errors.get("layout.sections") ?? [])];

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="max-w-2xl text-[13px] text-muted-foreground">
          Drag fields into sections to arrange the record form and detail page. Sections with the same tab name are grouped.
          Keyboard: focus a field, press space, move with the arrow keys, press space again.
        </p>
        <div className="flex gap-2">
          <Button variant="outline" disabled={!dirty || save.isPending} onClick={() => {
            setSections(original);
            setErrors(new Map());
          }}>
            <RotateCcw /> Discard
          </Button>
          <Button onClick={() => void onSave()} disabled={!dirty || save.isPending}>
            {save.isPending ? <Spinner className="text-primary-foreground" /> : <Save />} Save layout
          </Button>
        </div>
      </div>
      {generalErrors.length ? <FieldMessage error={generalErrors.join(" ")} /> : null}

      <DndContext sensors={sensors} collisionDetection={closestCorners} onDragStart={onDragStart} onDragOver={onDragOver} onDragEnd={onDragEnd} onDragCancel={() => setActiveId(null)}>
        <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_17rem]">
          <ol className="flex min-w-0 flex-col gap-4">
            {sections.map((section, index) => {
              const sectionErrors = violationsUnder(errors, `layout.sections[${index}]`);
              return (
                <li key={section.id} className="rounded-lg border border-border bg-card">
                  <div className="flex flex-wrap items-end gap-3 border-b border-border p-3">
                    <div className="grid min-w-40 flex-1 gap-1">
                      <Label htmlFor={`${section.id}-label`} className="text-xs">
                        Section
                      </Label>
                      <Input
                        id={`${section.id}-label`}
                        value={section.label}
                        className="h-8 text-[13px] font-medium"
                        onChange={(event) => patchSection(section.id, { label: event.target.value })}
                      />
                    </div>
                    <div className="grid w-36 gap-1">
                      <Label htmlFor={`${section.id}-tab`} className="text-xs">
                        Tab <span className="font-normal text-muted-foreground">(optional)</span>
                      </Label>
                      <Input
                        id={`${section.id}-tab`}
                        list={tabsListId}
                        value={section.tab}
                        placeholder="Details"
                        className="h-8 text-[13px]"
                        onChange={(event) => patchSection(section.id, { tab: event.target.value })}
                      />
                    </div>
                    <div className="grid gap-1">
                      <span className="text-xs font-medium" id={`${section.id}-cols`}>
                        Columns
                      </span>
                      <div role="radiogroup" aria-labelledby={`${section.id}-cols`} className="inline-flex h-8 items-center gap-0.5 rounded-md bg-muted p-0.5">
                        {[1, 2, 3, 4].map((n) => (
                          <button
                            key={n}
                            type="button"
                            role="radio"
                            aria-checked={section.columns === n}
                            onClick={() => patchSection(section.id, { columns: n })}
                            className={cn(
                              "focus-ring h-7 w-7 rounded-sm text-xs font-medium text-muted-foreground tabular",
                              section.columns === n && "bg-background text-foreground shadow-xs"
                            )}
                          >
                            {n}
                          </button>
                        ))}
                      </div>
                    </div>
                    <div className="flex items-center gap-0.5">
                      <Button variant="ghost" size="icon-sm" aria-label={`Move ${section.label} up`} disabled={index === 0} onClick={() => moveSection(index, -1)}>
                        <ArrowUp />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={`Move ${section.label} down`}
                        disabled={index === sections.length - 1}
                        onClick={() => moveSection(index, 1)}
                      >
                        <ArrowDown />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={`Remove ${section.label}`}
                        onClick={() => setSections((current) => current.filter((s) => s.id !== section.id))}
                      >
                        <Trash2 />
                      </Button>
                    </div>
                  </div>
                  <div className="p-3">
                    <FieldContainer
                      id={section.id}
                      columns={section.columns}
                      fields={section.fields.map((key) => byKey.get(key)).filter((f): f is FieldDefinition => Boolean(f))}
                      empty="Drop fields here"
                    />
                    <div className="mt-2 flex items-center gap-2 text-[11px] text-subtle-foreground">
                      <code className="font-mono">{section.key}</code>
                      {section.tab.trim() ? <Badge variant="outline">Tab: {section.tab.trim()}</Badge> : null}
                    </div>
                    {sectionErrors.length ? <FieldMessage error={sectionErrors.join(" ")} /> : null}
                  </div>
                </li>
              );
            })}
            <Button variant="outline" className="self-start" onClick={addSection} disabled={sections.length >= 50}>
              <Plus /> Add section
            </Button>
          </ol>
          <aside aria-label="Unplaced fields" className="rounded-lg border border-border bg-surface p-3 lg:sticky lg:top-16">
            <h3 className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
              <LayoutTemplate className="size-3.5" /> Not in the layout ({pool.length})
            </h3>
            <p className="mb-2 mt-0.5 text-[11px] text-subtle-foreground">Shown in an “Other fields” section.</p>
            <FieldContainer id={POOL} columns={1} fields={pool} empty="Every field is placed" />
          </aside>
        </div>
        <DragOverlay dropAnimation={null}>{activeField ? <FieldChip field={activeField} overlay columns={1} /> : null}</DragOverlay>
      </DndContext>
      <datalist id={tabsListId}>
        {tabNames.map((name) => (
          <option key={name} value={name} />
        ))}
      </datalist>
    </div>
  );
}
