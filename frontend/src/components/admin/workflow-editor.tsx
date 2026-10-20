"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { toast } from "sonner";
import { AlertTriangle, ArrowRight, ChevronRight, GitBranch, Info, Plus, RotateCcw, Save, Trash2, Workflow as WorkflowIcon } from "lucide-react";

import { AdminGate, FieldMessage, toApiKey } from "@/components/admin/admin-gate";
import { useOrg } from "@/components/app/org-context";
import { ErrorState } from "@/components/common/error-state";
import { ModuleGlyph } from "@/components/common/module-glyph";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { StateBadge, StateDot } from "@/components/workflow/state-badge";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { MultiCombobox } from "@/components/ui/multi-combobox";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { ApiError, useDeleteWorkflow, useModule, useRoles, useSaveWorkflow, useWorkflow } from "@/lib/api";
import { violationMap, violationsUnder } from "@/lib/api/violations";
import { activeFields, selectOptions } from "@/lib/metadata/fields";
import { adminPath } from "@/lib/navigation";
import { ANY_ROLE, CATEGORY_DESCRIPTIONS, CATEGORY_LABELS, STATE_COLORS, STATE_PALETTE, isValidStateColor } from "@/lib/workflow";
import { cn } from "@/lib/utils";
import type { FieldDefinition, ModuleDefinition } from "@/types/metadata";
import {
  WORKFLOW_CATEGORIES,
  type Workflow,
  type WorkflowCategory,
  type WorkflowDefinitionBody,
  type WorkflowState,
  type WorkflowTransitionDefinition,
} from "@/types/workflow";

/* ---------------------------------------------------------------- index */

function WorkflowRow({ orgId, module }: { orgId: string; module: ModuleDefinition }) {
  const workflow = useWorkflow(orgId, module.apiKey);
  const field = workflow.data ? module.fields.find((f) => f.apiKey === workflow.data!.field) : undefined;
  return (
    <li>
      <Link
        href={adminPath(orgId, "workflows", `/${module.apiKey}`)}
        className="focus-ring flex items-center gap-3 px-4 py-3 transition-colors hover:bg-surface"
      >
        <span className="grid size-8 shrink-0 place-items-center rounded-md border border-border bg-background text-muted-foreground">
          <ModuleGlyph moduleKey={module.apiKey} className="size-4" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-[13px] font-medium">{module.labelPlural}</p>
          <p className="truncate text-xs text-muted-foreground">
            {workflow.isLoading
              ? "Loading…"
              : workflow.data
                ? `${field?.label ?? workflow.data.field} · ${workflow.data.definition.states.length} stages · ${workflow.data.definition.transitions.length} moves`
                : "No workflow"}
          </p>
        </div>
        {workflow.data ? (
          <div className="hidden max-w-[50%] flex-wrap justify-end gap-1 md:flex">
            {workflow.data.definition.states.slice(0, 6).map((state) => (
              <StateBadge key={state.key} state={state} />
            ))}
            {workflow.data.definition.states.length > 6 ? (
              <Badge variant="outline">+{workflow.data.definition.states.length - 6}</Badge>
            ) : null}
          </div>
        ) : workflow.isLoading ? (
          <Skeleton className="h-5 w-24" />
        ) : (
          <Badge variant="outline">Set up</Badge>
        )}
        <ChevronRight className="size-4 shrink-0 text-subtle-foreground" />
      </Link>
    </li>
  );
}

export function WorkflowsIndexPage() {
  const { orgId, modules } = useOrg();
  const candidates = modules.filter((module) => activeFields(module).some((field) => field.type === "SINGLE_SELECT"));
  return (
    <AdminGate>
      <PageHeader
        title="Workflows"
        description="A workflow turns a pick-list field into stages. Records can only move along the transitions you allow, and every move is recorded."
      />
      <PageBody>
        {candidates.length === 0 ? (
          <EmptyState
            icon={WorkflowIcon}
            title="No module has a pick-list field yet"
            description="Add a single-select field (for example “Stage”) to a module in Customize modules first."
          />
        ) : (
          <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border bg-card">
            {candidates.map((module) => (
              <WorkflowRow key={module.apiKey} orgId={orgId} module={module} />
            ))}
          </ul>
        )}
      </PageBody>
    </AdminGate>
  );
}

/* ---------------------------------------------------------------- editor */

interface StateDraft extends WorkflowState {
  uid: string;
}

interface TransitionDraft extends WorkflowTransitionDefinition {
  uid: string;
  keyEdited: boolean;
}

let uidCounter = 0;
const uid = () => `d${++uidCounter}`;

function statesFromField(field: FieldDefinition | undefined): StateDraft[] {
  const options = field ? selectOptions(field).filter((option) => !option.deprecated) : [];
  return options.map((option, index) => ({
    uid: uid(),
    key: option.value,
    label: option.label,
    category: index === 0 ? "OPEN" : index === options.length - 1 ? "WON" : "IN_PROGRESS",
    color: STATE_PALETTE[(index * 3) % STATE_PALETTE.length],
  }));
}

function toDraft(workflow: Workflow | null | undefined, field: FieldDefinition | undefined) {
  if (workflow) {
    return {
      field: workflow.field,
      initial: workflow.definition.initial,
      states: workflow.definition.states.map((state) => ({ ...state, uid: uid() })),
      transitions: workflow.definition.transitions.map((t) => ({
        ...t,
        requireComment: Boolean(t.requireComment),
        uid: uid(),
        keyEdited: true,
      })),
    };
  }
  const states = statesFromField(field);
  const defaultValue = typeof field?.defaultValue === "string" ? field.defaultValue : undefined;
  return {
    field: field?.apiKey ?? "",
    initial: defaultValue ?? states[0]?.key ?? "",
    states,
    transitions: [] as TransitionDraft[],
  };
}

type Draft = ReturnType<typeof toDraft>;

function toDefinition(draft: Draft): WorkflowDefinitionBody {
  return {
    initial: draft.initial,
    states: draft.states.map(({ key, label, category, color }) => ({ key, label: label.trim(), category, ...(color ? { color } : {}) })),
    transitions: draft.transitions.map(({ key, label, from, to, roles, requireComment }) => ({
      key,
      label: label.trim(),
      from,
      to,
      roles,
      requireComment: Boolean(requireComment),
    })),
  };
}

function clientViolations(draft: Draft, field: FieldDefinition | undefined) {
  const map = new Map<string, string[]>();
  const add = (path: string, message: string) => map.set(path, [...(map.get(path) ?? []), message]);
  const optionValues = new Set((field ? selectOptions(field) : []).map((o) => o.value));
  const stateKeys = new Set<string>();
  if (!draft.field) add("field", "Pick the field that holds the stage");
  if (draft.states.length === 0) add("definition.states", "Add at least one stage");
  draft.states.forEach((state, i) => {
    if (!optionValues.has(state.key)) add(`definition.states[${i}].key`, `“${state.key}” is not an option of the field`);
    if (stateKeys.has(state.key)) add(`definition.states[${i}].key`, "Used twice");
    stateKeys.add(state.key);
    if (!state.label.trim()) add(`definition.states[${i}].label`, "Enter a label");
    if (state.color && !isValidStateColor(state.color)) add(`definition.states[${i}].color`, "Use a palette color or a hex value");
  });
  if (!stateKeys.has(draft.initial)) add("definition.initial", "Pick one of the stages");
  const transitionKeys = new Set<string>();
  draft.transitions.forEach((t, i) => {
    const p = `definition.transitions[${i}]`;
    if (!/^[a-z][a-z0-9_]{0,62}$/.test(t.key)) add(`${p}.key`, "Use lowercase letters, digits and underscores");
    if (transitionKeys.has(t.key)) add(`${p}.key`, "Used twice");
    transitionKeys.add(t.key);
    if (!t.label.trim()) add(`${p}.label`, "Enter a label");
    if (t.from.length === 0) add(`${p}.from`, "Pick at least one stage to move from");
    if (!t.to || !stateKeys.has(t.to)) add(`${p}.to`, "Pick the stage to move to");
    if (t.from.includes(t.to)) add(`${p}.from`, "A move can't lead back to one of its own starting stages");
    if (t.roles.length === 0) add(`${p}.roles`, "Pick who may make this move");
  });
  return map;
}

function ColorPicker({ value, onChange, label }: { value: string | null | undefined; onChange: (color: string) => void; label: string }) {
  const [hex, setHex] = React.useState(value?.startsWith("#") ? value : "");
  const current = value ?? "";
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="outline" size="icon-sm" aria-label={`${label} color: ${current || "default"}`}>
          <StateDot state={{ color: current, category: "OPEN" }} className="size-3.5" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-60" align="start">
        <p className="mb-2 text-xs font-medium text-muted-foreground">Color</p>
        <div className="grid grid-cols-7 gap-1.5" role="radiogroup" aria-label="Palette">
          {STATE_PALETTE.map((name) => (
            <button
              key={name}
              type="button"
              role="radio"
              aria-checked={current === name}
              aria-label={name}
              title={name}
              onClick={() => onChange(name)}
              className={cn(
                "focus-ring grid size-6 place-items-center rounded-md border border-transparent hover:border-border-strong",
                current === name && "border-foreground"
              )}
            >
              <span className={cn("size-3.5 rounded-full", STATE_COLORS[name].dot)} />
            </button>
          ))}
        </div>
        <div className="mt-3 flex items-center gap-2">
          <Input
            aria-label="Hex color"
            placeholder="#10b981"
            value={hex}
            className="h-8 font-mono text-xs"
            onChange={(event) => setHex(event.target.value)}
          />
          <Button size="sm" variant="outline" disabled={!/^#[0-9a-f]{6}$/i.test(hex)} onClick={() => onChange(hex.toLowerCase())}>
            Use
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  );
}

function StatesEditor({
  moduleKey,
  field,
  draft,
  setDraft,
  errors,
}: {
  moduleKey: string;
  field: FieldDefinition | undefined;
  draft: Draft;
  setDraft: React.Dispatch<React.SetStateAction<Draft>>;
  errors: Map<string, string[]>;
}) {
  const { orgId } = useOrg();
  const options = field ? selectOptions(field) : [];
  const used = new Set(draft.states.map((s) => s.key));
  const missing = options.filter((option) => !used.has(option.value) && !option.deprecated);
  const patchState = (index: number, patch: Partial<StateDraft>) =>
    setDraft((d) => ({ ...d, states: d.states.map((s, i) => (i === index ? { ...s, ...patch } : s)) }));
  const move = (index: number, delta: number) =>
    setDraft((d) => {
      const next = [...d.states];
      const target = index + delta;
      if (target < 0 || target >= next.length) return d;
      [next[index], next[target]] = [next[target], next[index]];
      return { ...d, states: next };
    });
  const remove = (index: number) =>
    setDraft((d) => {
      const removed = d.states[index]?.key;
      return {
        ...d,
        states: d.states.filter((_, i) => i !== index),
        transitions: d.transitions
          .map((t) => ({ ...t, from: t.from.filter((f) => f !== removed) }))
          .map((t) => (t.to === removed ? { ...t, to: "" } : t)),
      };
    });

  const topErrors = errors.get("definition.states") ?? [];

  return (
    <Card>
      <CardHeader>
        <CardTitle>Stages</CardTitle>
        <CardDescription>
          Stages are the options of the <span className="font-medium text-foreground">{field?.label ?? "selected"}</span> field, in board order.
          Lost stages are collapsed at the end of the board.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {topErrors.map((message) => (
          <p key={message} className="text-xs text-destructive">{message}</p>
        ))}
        <div className="hidden grid-cols-[1.75rem_minmax(0,8rem)_minmax(8rem,1fr)_9rem_2rem_auto] gap-2 px-1 text-xs font-medium text-muted-foreground lg:grid">
          <span />
          <span>Option</span>
          <span>Label</span>
          <span>Category</span>
          <span>Color</span>
          <span className="sr-only">Actions</span>
        </div>
        <ol className="flex flex-col gap-2">
          {draft.states.map((state, index) => {
            const p = `definition.states[${index}]`;
            const rowErrors = violationsUnder(errors, p);
            const isInitial = draft.initial === state.key;
            return (
              <li key={state.uid} className="rounded-lg border border-border bg-background p-2">
                <div className="grid grid-cols-[1.75rem_minmax(0,1fr)] items-center gap-2 lg:grid-cols-[1.75rem_minmax(0,8rem)_minmax(8rem,1fr)_9rem_2rem_auto]">
                  <span className="grid size-7 place-items-center text-xs text-muted-foreground tabular">{index + 1}</span>
                  <div className="flex min-w-0 items-center gap-1.5">
                    <code className="truncate rounded bg-muted px-1.5 py-0.5 font-mono text-xs" title={state.key}>
                      {state.key}
                    </code>
                    {isInitial ? <Badge variant="default">Initial</Badge> : null}
                  </div>
                  <Input
                    aria-label={`Label for ${state.key}`}
                    value={state.label}
                    className="col-span-2 h-8 text-[13px] lg:col-span-1"
                    aria-invalid={Boolean(errors.get(`${p}.label`)) || undefined}
                    onChange={(event) => patchState(index, { label: event.target.value })}
                  />
                  <Select value={state.category} onValueChange={(category) => patchState(index, { category: category as WorkflowCategory })}>
                    <SelectTrigger size="sm" aria-label={`Category for ${state.key}`} className="col-span-2 lg:col-span-1">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {WORKFLOW_CATEGORIES.map((category) => (
                        <SelectItem key={category} value={category} title={CATEGORY_DESCRIPTIONS[category]}>
                          {CATEGORY_LABELS[category]}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <ColorPicker label={state.label || state.key} value={state.color} onChange={(color) => patchState(index, { color })} />
                  <div className="flex items-center gap-0.5">
                    <Button variant="ghost" size="icon-xs" aria-label={`Move ${state.label} up`} disabled={index === 0} onClick={() => move(index, -1)}>
                      <ArrowRight className="-rotate-90" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon-xs"
                      aria-label={`Move ${state.label} down`}
                      disabled={index === draft.states.length - 1}
                      onClick={() => move(index, 1)}
                    >
                      <ArrowRight className="rotate-90" />
                    </Button>
                    <Button variant="ghost" size="icon-xs" aria-label={`Remove ${state.label}`} onClick={() => remove(index)}>
                      <Trash2 />
                    </Button>
                  </div>
                </div>
                {rowErrors.length > 0 ? (
                  <ul className="mt-1.5 pl-9 text-xs text-destructive">
                    {rowErrors.map((message, i) => (
                      <li key={i}>{message}</li>
                    ))}
                  </ul>
                ) : null}
              </li>
            );
          })}
        </ol>
        <div className="flex flex-wrap items-center gap-2">
          {missing.length > 0 ? (
            <Select
              value=""
              onValueChange={(value) => {
                const option = missing.find((o) => o.value === value);
                if (!option) return;
                setDraft((d) => ({
                  ...d,
                  states: [...d.states, { uid: uid(), key: option.value, label: option.label, category: "IN_PROGRESS", color: null }],
                }));
              }}
            >
              <SelectTrigger size="sm" className="w-56" aria-label="Add a stage">
                <Plus className="size-3.5" /> <SelectValue placeholder="Add a stage from the field" />
              </SelectTrigger>
              <SelectContent>
                {missing.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    {option.label} <span className="font-mono text-xs text-muted-foreground">{option.value}</span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          ) : null}
          <p className="flex items-start gap-1.5 text-xs text-muted-foreground">
            <Info className="mt-px size-3.5 shrink-0" />
            <span>
              Need another stage? Add the option to the field in{" "}
              <Link className="text-primary hover:underline" href={adminPath(orgId, "modules", `/${moduleKey}`)}>
                Customize modules
              </Link>{" "}
              first; options can&apos;t be created here.
            </span>
          </p>
        </div>
      </CardContent>
    </Card>
  );
}

function TransitionsEditor({
  draft,
  setDraft,
  errors,
  roleOptions,
}: {
  draft: Draft;
  setDraft: React.Dispatch<React.SetStateAction<Draft>>;
  errors: Map<string, string[]>;
  roleOptions: { value: string; label: string; description?: string }[];
}) {
  const stateOptions = draft.states.map((state) => ({ value: state.key, label: state.label || state.key, render: <StateBadge state={state} /> }));
  const patch = (index: number, value: Partial<TransitionDraft>) =>
    setDraft((d) => ({
      ...d,
      transitions: d.transitions.map((t, i) => {
        if (i !== index) return t;
        const next = { ...t, ...value };
        if (value.label !== undefined && !t.keyEdited) next.key = toApiKey(value.label) || t.key;
        return next;
      }),
    }));
  const add = () =>
    setDraft((d) => ({
      ...d,
      transitions: [
        ...d.transitions,
        { uid: uid(), key: `move_${d.transitions.length + 1}`, label: "", from: [], to: "", roles: [ANY_ROLE], requireComment: false, keyEdited: false },
      ],
    }));

  return (
    <Card>
      <CardHeader>
        <CardTitle>Moves</CardTitle>
        <CardDescription>
          Each move goes from one or more stages to a target stage. Admins can make every move; other members need one of the
          listed roles.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {draft.transitions.length === 0 ? (
          <p className="rounded-lg border border-dashed border-border px-4 py-6 text-center text-[13px] text-muted-foreground">
            No moves yet. Without moves, records stay in their initial stage.
          </p>
        ) : null}
        {draft.transitions.map((t, index) => {
          const p = `definition.transitions[${index}]`;
          const err = (key: string) => (errors.get(`${p}.${key}`) ?? []).join(" ");
          const other = (errors.get(p) ?? []).join(" ");
          return (
            <fieldset key={t.uid} className="grid gap-3 rounded-lg border border-border bg-background p-3 md:grid-cols-2">
              <legend className="sr-only">Move {index + 1}</legend>
              <div className="grid gap-1.5">
                <Label htmlFor={`${t.uid}-label`}>Button label</Label>
                <Input
                  id={`${t.uid}-label`}
                  value={t.label}
                  placeholder="e.g. Shortlist"
                  className="h-8 text-[13px]"
                  aria-invalid={Boolean(err("label")) || undefined}
                  onChange={(event) => patch(index, { label: event.target.value })}
                />
                <FieldMessage error={err("label")} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor={`${t.uid}-key`}>Key</Label>
                <Input
                  id={`${t.uid}-key`}
                  value={t.key}
                  className="h-8 font-mono text-xs"
                  aria-invalid={Boolean(err("key")) || undefined}
                  onChange={(event) => patch(index, { key: event.target.value, keyEdited: true })}
                />
                <FieldMessage error={err("key")} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor={`${t.uid}-from`}>From</Label>
                <MultiCombobox
                  id={`${t.uid}-from`}
                  value={t.from}
                  onChange={(from) => patch(index, { from })}
                  options={stateOptions.filter((o) => o.value !== t.to)}
                  placeholder="Pick stages"
                  invalid={Boolean(err("from"))}
                />
                <FieldMessage error={err("from")} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor={`${t.uid}-to`}>To</Label>
                <Select value={t.to} onValueChange={(to) => patch(index, { to, from: t.from.filter((f) => f !== to) })}>
                  <SelectTrigger id={`${t.uid}-to`} size="sm" aria-invalid={Boolean(err("to")) || undefined}>
                    <SelectValue placeholder="Pick a stage" />
                  </SelectTrigger>
                  <SelectContent>
                    {draft.states.map((state) => (
                      <SelectItem key={state.uid} value={state.key}>
                        <StateDot state={state} /> {state.label || state.key}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FieldMessage error={err("to")} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor={`${t.uid}-roles`}>Who may move</Label>
                <MultiCombobox
                  id={`${t.uid}-roles`}
                  value={t.roles}
                  onChange={(roles) => {
                    const addedEveryone = roles.includes(ANY_ROLE) && !t.roles.includes(ANY_ROLE);
                    patch(index, { roles: addedEveryone ? [ANY_ROLE] : roles.filter((r) => r !== ANY_ROLE || roles.length === 1) });
                  }}
                  options={roleOptions}
                  placeholder="Pick roles"
                  invalid={Boolean(err("roles"))}
                />
                <FieldMessage error={err("roles")} hint="Admins can always make every move." />
              </div>
              <div className="flex items-end justify-between gap-3">
                <label className="flex items-center gap-2 text-[13px]">
                  <Switch checked={Boolean(t.requireComment)} onCheckedChange={(requireComment) => patch(index, { requireComment })} />
                  Require a comment
                </label>
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-destructive"
                  onClick={() => setDraft((d) => ({ ...d, transitions: d.transitions.filter((_, i) => i !== index) }))}
                >
                  <Trash2 /> Remove
                </Button>
              </div>
              {other ? <p className="text-xs text-destructive md:col-span-2">{other}</p> : null}
            </fieldset>
          );
        })}
        <Button variant="outline" size="sm" className="self-start" onClick={add} disabled={draft.states.length < 2}>
          <Plus /> Add a move
        </Button>
      </CardContent>
    </Card>
  );
}

/** Simple state diagram: stages in order, each with its outgoing moves. */
function WorkflowPreview({ draft, roleLabel }: { draft: Draft; roleLabel: (key: string) => string }) {
  const byKey = new Map(draft.states.map((s) => [s.key, s]));
  const incoming = new Map<string, number>();
  draft.transitions.forEach((t) => incoming.set(t.to, (incoming.get(t.to) ?? 0) + 1));
  return (
    <Card>
      <CardHeader>
        <CardTitle>Preview</CardTitle>
        <CardDescription>Moves leaving each stage.</CardDescription>
      </CardHeader>
      <CardContent className="pt-4">
        <ol className="relative flex flex-col gap-3">
          {draft.states.map((state) => {
            const outgoing = draft.transitions.filter((t) => t.from.includes(state.key));
            const unreachable = state.key !== draft.initial && !incoming.get(state.key);
            return (
              <li key={state.uid} className="rounded-lg border border-border bg-background p-2.5">
                <div className="flex items-center gap-2">
                  <StateBadge state={state} />
                  {state.key === draft.initial ? <Badge variant="default">Start</Badge> : null}
                  {unreachable ? (
                    <span className="inline-flex items-center gap-1 text-[11px] text-warning-soft-foreground">
                      <AlertTriangle className="size-3" /> Unreachable
                    </span>
                  ) : null}
                </div>
                {outgoing.length > 0 ? (
                  <ul className="mt-2 flex flex-col gap-1.5 border-l border-border pl-3">
                    {outgoing.map((t) => {
                      const target = byKey.get(t.to);
                      return (
                        <li key={t.uid} className="flex flex-wrap items-center gap-1.5 text-xs">
                          <span className="font-medium">{t.label || t.key}</span>
                          <ArrowRight className="size-3 text-subtle-foreground" aria-label="to" />
                          {target ? <StateBadge state={target} /> : <span className="text-destructive">no target</span>}
                          <span className="text-subtle-foreground">
                            {t.roles.includes(ANY_ROLE) ? "everyone" : t.roles.map(roleLabel).join(", ")}
                            {t.requireComment ? " · comment" : ""}
                          </span>
                        </li>
                      );
                    })}
                  </ul>
                ) : (
                  <p className="mt-1.5 text-xs text-subtle-foreground">
                    {state.category === "WON" || state.category === "LOST" ? "Final stage" : "No moves out"}
                  </p>
                )}
              </li>
            );
          })}
        </ol>
      </CardContent>
    </Card>
  );
}

function WorkflowEditor({ module, workflow }: { module: ModuleDefinition; workflow: Workflow | null }) {
  const router = useRouter();
  const { orgId } = useOrg();
  const roles = useRoles(orgId);
  const save = useSaveWorkflow(orgId, module.apiKey);
  const remove = useDeleteWorkflow(orgId, module.apiKey);
  const workflowQuery = useWorkflow(orgId, module.apiKey);
  const selectFields = activeFields(module).filter((f) => f.type === "SINGLE_SELECT");
  const initialField = selectFields.find((f) => f.apiKey === workflow?.field) ?? selectFields[0];
  const [original] = React.useState(() => toDraft(workflow, initialField));
  const [draft, setDraft] = React.useState<Draft>(original);
  const [serverErrors, setServerErrors] = React.useState<Map<string, string[]>>(new Map());
  const [submitted, setSubmitted] = React.useState(false);
  const [confirmDelete, setConfirmDelete] = React.useState(false);
  const [conflict, setConflict] = React.useState(false);
  const field = selectFields.find((f) => f.apiKey === draft.field);

  const clientErrors = clientViolations(draft, field);
  const errors = submitted ? (serverErrors.size > 0 ? serverErrors : clientErrors) : serverErrors;
  const dirty = JSON.stringify(toDefinition(draft)) !== JSON.stringify(toDefinition(original)) || draft.field !== original.field;

  const roleOptions = React.useMemo(
    () => [
      { value: ANY_ROLE, label: "Everyone", description: "Every member with update access" },
      ...(roles.data ?? []).map((role) => ({ value: role.apiKey, label: role.label, description: role.apiKey })),
    ],
    [roles.data]
  );
  const roleLabel = (key: string) => roles.data?.find((r) => r.apiKey === key)?.label ?? key;
  const defaultValue = typeof field?.defaultValue === "string" ? field.defaultValue : null;

  const onSave = async () => {
    setSubmitted(true);
    setServerErrors(new Map());
    if (clientErrors.size > 0) {
      toast.error("Fix the highlighted problems first");
      return;
    }
    try {
      await save.mutateAsync({ field: draft.field, definition: toDefinition(draft), ifMatch: workflow?.version });
      toast.success(workflow ? "Workflow saved" : "Workflow created");
      setSubmitted(false);
    } catch (error) {
      if (error instanceof ApiError && error.isValidation) {
        setServerErrors(violationMap(error));
        toast.error("The server rejected the workflow", { description: "See the highlighted problems." });
      } else if (error instanceof ApiError && error.isVersionConflict) {
        setConflict(true);
      }
    }
  };

  const generalErrors = [...(errors.get("field") ?? []), ...(errors.get("definition.initial") ?? []), ...(errors.get("definition") ?? [])];
  const otherServer = [...serverErrors.keys()].filter(
    (path) => !path.startsWith("definition.states") && !path.startsWith("definition.transitions") && !["field", "definition.initial", "definition"].includes(path)
  );

  return (
    <>
      <PageHeader
        icon={
          <span className="mt-0.5 grid size-9 shrink-0 place-items-center rounded-lg border border-border bg-surface text-muted-foreground">
            <ModuleGlyph moduleKey={module.apiKey} className="size-[18px]" />
          </span>
        }
        title={`${module.labelPlural} workflow`}
        description={workflow ? `Version ${workflow.version}. Changes apply to every new move; existing records keep their stage.` : "Not set up yet."}
        actions={
          <>
            {workflow ? (
              <Button variant="destructive-outline" onClick={() => setConfirmDelete(true)}>
                <Trash2 /> Remove
              </Button>
            ) : null}
            <Button variant="outline" disabled={!dirty || save.isPending} onClick={() => {
              setDraft(original);
              setServerErrors(new Map());
              setSubmitted(false);
            }}>
              <RotateCcw /> Discard
            </Button>
            <Button onClick={() => void onSave()} disabled={save.isPending || (!dirty && Boolean(workflow))}>
              {save.isPending ? <Spinner className="text-primary-foreground" /> : <Save />}
              {workflow ? "Save workflow" : "Create workflow"}
            </Button>
          </>
        }
      />
      <PageBody>
        {conflict ? (
          <div role="alert" className="mb-4 flex flex-wrap items-center gap-3 rounded-lg border border-warning/30 bg-warning-soft px-4 py-3 text-[13px] text-warning-soft-foreground">
            <AlertTriangle className="size-4" />
            Someone else changed this workflow. Reload to get the latest version; your edits will be lost.
            <Button
              size="sm"
              variant="outline"
              className="ml-auto"
              onClick={async () => {
                await workflowQuery.refetch();
                setConflict(false);
              }}
            >
              Reload
            </Button>
          </div>
        ) : null}
        <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_22rem]">
          <div className="flex min-w-0 flex-col gap-6">
            <Card>
              <CardHeader>
                <CardTitle>Stage field</CardTitle>
                <CardDescription>The single-select field that holds each record&apos;s stage.</CardDescription>
              </CardHeader>
              <CardContent className="grid gap-4 sm:grid-cols-2">
                <div className="grid gap-1.5">
                  <Label htmlFor="wf-field">Field</Label>
                  <Select
                    value={draft.field}
                    disabled={Boolean(workflow)}
                    onValueChange={(key) => {
                      const next = selectFields.find((f) => f.apiKey === key);
                      setDraft(toDraft(null, next));
                      setServerErrors(new Map());
                    }}
                  >
                    <SelectTrigger id="wf-field" aria-invalid={Boolean(errors.get("field")) || undefined}>
                      <SelectValue placeholder="Pick a field" />
                    </SelectTrigger>
                    <SelectContent>
                      {selectFields.map((f) => (
                        <SelectItem key={f.apiKey} value={f.apiKey}>
                          {f.label} <span className="font-mono text-xs text-muted-foreground">{f.apiKey}</span>
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FieldMessage
                    error={(errors.get("field") ?? []).join(" ")}
                    hint={workflow ? "Remove the workflow to use another field." : undefined}
                  />
                </div>
                <div className="grid gap-1.5">
                  <Label htmlFor="wf-initial">Initial stage</Label>
                  <Select value={draft.initial} onValueChange={(initial) => setDraft((d) => ({ ...d, initial }))}>
                    <SelectTrigger id="wf-initial" aria-invalid={Boolean(errors.get("definition.initial")) || undefined}>
                      <SelectValue placeholder="Pick a stage" />
                    </SelectTrigger>
                    <SelectContent>
                      {draft.states.map((state) => (
                        <SelectItem key={state.uid} value={state.key}>
                          <StateDot state={state} /> {state.label || state.key}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FieldMessage
                    error={(errors.get("definition.initial") ?? []).join(" ")}
                    hint={
                      defaultValue !== draft.initial ? (
                        <span className="inline-flex items-start gap-1 text-warning-soft-foreground">
                          <AlertTriangle className="mt-px size-3 shrink-0" />
                          The field&apos;s default value ({defaultValue ?? "none"}) must be the initial stage. Change the default in Customize
                          modules.
                        </span>
                      ) : (
                        "New records start here (it's the field's default value)."
                      )
                    }
                  />
                </div>
              </CardContent>
            </Card>
            {generalErrors.length > 0 || otherServer.length > 0 ? (
              <div role="alert" className="rounded-lg border border-destructive/20 bg-destructive-soft px-4 py-3 text-[13px] text-destructive-soft-foreground">
                <ul className="list-disc pl-4">
                  {generalErrors.map((message, i) => (
                    <li key={`g-${i}`}>{message}</li>
                  ))}
                  {otherServer.map((path) => (
                    <li key={path}>
                      <code className="font-mono text-xs">{path}</code>: {serverErrors.get(path)?.join(" ")}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
            <StatesEditor moduleKey={module.apiKey} field={field} draft={draft} setDraft={setDraft} errors={errors} />
            <TransitionsEditor draft={draft} setDraft={setDraft} errors={errors} roleOptions={roleOptions} />
          </div>
          <div className="xl:sticky xl:top-16">
            <WorkflowPreview draft={draft} roleLabel={roleLabel} />
          </div>
        </div>
      </PageBody>

      <AlertDialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Remove the {module.labelPlural.toLowerCase()} workflow?</AlertDialogTitle>
            <AlertDialogDescription>
              The field stays a normal pick-list: anyone who can edit a record can set any value. Stage history is kept.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={async (event) => {
                event.preventDefault();
                if (!workflow) return;
                await remove.mutateAsync(workflow.version).catch(() => undefined);
                setConfirmDelete(false);
                router.push(adminPath(orgId, "workflows"));
              }}
            >
              {remove.isPending ? <Spinner className="text-destructive-foreground" /> : null}
              Remove workflow
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}

export function WorkflowEditorPage({ moduleKey }: { moduleKey: string }) {
  const { orgId } = useOrg();
  const moduleQuery = useModule(orgId, moduleKey);
  const workflow = useWorkflow(orgId, moduleKey);
  return (
    <AdminGate>
      {moduleQuery.isLoading || workflow.isLoading ? (
        <div className="flex flex-col gap-4 px-4 pt-6 sm:px-6 lg:px-8">
          <Skeleton className="h-6 w-56" />
          <Skeleton className="h-40 w-full rounded-lg" />
          <Skeleton className="h-72 w-full rounded-lg" />
        </div>
      ) : moduleQuery.isError || !moduleQuery.data ? (
        <ErrorState error={moduleQuery.error} onRetry={() => void moduleQuery.refetch()} />
      ) : workflow.isError ? (
        <ErrorState error={workflow.error} onRetry={() => void workflow.refetch()} />
      ) : activeFields(moduleQuery.data).some((f) => f.type === "SINGLE_SELECT") ? (
        <WorkflowEditor key={`${workflow.data?.version ?? "new"}`} module={moduleQuery.data} workflow={workflow.data ?? null} />
      ) : (
        <EmptyState
          icon={GitBranch}
          title={`${moduleQuery.data.labelPlural} have no pick-list field`}
          description="Add a single-select field for the stage in Customize modules, then come back."
          action={
            <Button asChild>
              <Link href={adminPath(orgId, "modules", `/${moduleKey}`)}>Customize {moduleQuery.data.labelPlural.toLowerCase()}</Link>
            </Button>
          }
        />
      )}
    </AdminGate>
  );
}
