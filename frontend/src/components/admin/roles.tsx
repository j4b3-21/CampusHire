"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { toast } from "sonner";
import { Braces, ChevronDown, ChevronRight, Plus, Save, ShieldCheck, Table2, Trash2, X } from "lucide-react";

import { AdminGate, API_KEY_PATTERN, FieldMessage, toApiKey } from "@/components/admin/admin-gate";
import { useOrg } from "@/components/app/org-context";
import { ErrorState } from "@/components/common/error-state";
import { ModuleGlyph } from "@/components/common/module-glyph";
import { PageBody, PageHeader } from "@/components/layout/page-header";
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
import { Checkbox } from "@/components/ui/checkbox";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { MultiCombobox } from "@/components/ui/multi-combobox";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { SegmentedList, SegmentedTrigger, Tabs } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { ApiError, errorMessage, useCreateRole, useDeleteRole, useRoles, useUpdateRole } from "@/lib/api";
import { violationMap, violationsUnder } from "@/lib/api/violations";
import { activeFields, selectOptions } from "@/lib/metadata/fields";
import { formatRelative } from "@/lib/metadata/format";
import { adminPath } from "@/lib/navigation";
import {
  RECORD_ID_FIELD,
  RULE_ACTIONS,
  SCOPE_HINTS,
  SCOPE_LABELS,
  emptyModuleDraft,
  fromDocument,
  parsePermissionJson,
  toDocument,
  type ModuleDraft,
  type RuleAction,
  type RuleDraft,
} from "@/lib/permissions";
import { cn } from "@/lib/utils";
import type { OrgRole, RuleScope } from "@/types/access";
import type { ModuleDefinition } from "@/types/metadata";

/* ---------------------------------------------------------------- list */

export function RolesIndexPage() {
  const { orgId } = useOrg();
  const roles = useRoles(orgId);
  return (
    <AdminGate>
      <PageHeader
        title="Roles & access"
        description="Org roles decide which modules members can use, which records they see and which fields they can change. Owners and admins always have full access."
        actions={
          <Button asChild>
            <Link href={adminPath(orgId, "roles", "/new")}>
              <Plus /> New role
            </Link>
          </Button>
        }
      />
      <PageBody>
        {roles.isLoading ? (
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {Array.from({ length: 4 }, (_, i) => (
              <Skeleton key={i} className="h-28 rounded-lg" />
            ))}
          </div>
        ) : roles.isError ? (
          <ErrorState error={roles.error} onRetry={() => void roles.refetch()} />
        ) : (roles.data ?? []).length === 0 ? (
          <EmptyState
            icon={ShieldCheck}
            title="No roles yet"
            description="Create roles such as Coordinator or Recruiter, then assign them to members and invitations."
            action={
              <Button asChild>
                <Link href={adminPath(orgId, "roles", "/new")}>
                  <Plus /> New role
                </Link>
              </Button>
            }
          />
        ) : (
          <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {(roles.data ?? []).map((role) => {
              const modules = Object.keys(role.permissions?.modules ?? {});
              return (
                <li key={role.apiKey}>
                  <Link
                    href={adminPath(orgId, "roles", `/${role.apiKey}`)}
                    className="focus-ring flex h-full flex-col gap-2 rounded-lg border border-border bg-card p-4 transition-colors hover:border-border-strong hover:bg-surface"
                  >
                    <div className="flex items-center gap-2">
                      <ShieldCheck className="size-4 text-primary" />
                      <span className="truncate text-sm font-semibold">{role.label}</span>
                      <code className="ml-auto truncate rounded bg-muted px-1.5 py-0.5 font-mono text-[11px] text-muted-foreground">
                        {role.apiKey}
                      </code>
                    </div>
                    {role.description ? (
                      <p className="line-clamp-2 text-[13px] text-muted-foreground">{role.description}</p>
                    ) : null}
                    <p className="mt-auto text-xs text-subtle-foreground">
                      {modules.length} {modules.length === 1 ? "module" : "modules"} · updated {formatRelative(role.updatedAt)}
                    </p>
                  </Link>
                </li>
              );
            })}
          </ul>
        )}
      </PageBody>
    </AdminGate>
  );
}

/* ---------------------------------------------------------------- matrix */

const SCOPES: RuleScope[] = ["NONE", "ALL", "OWN", "ATTRIBUTE"];

function ScopeSelect({ value, onChange, label }: { value: RuleScope; onChange: (scope: RuleScope) => void; label: string }) {
  return (
    <Select value={value} onValueChange={(scope) => onChange(scope as RuleScope)}>
      <SelectTrigger size="sm" aria-label={label} className={cn("h-7 w-full min-w-[6.5rem] text-xs", value === "NONE" && "text-muted-foreground")}>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {SCOPES.map((scope) => (
          <SelectItem key={scope} value={scope} title={SCOPE_HINTS[scope]}>
            {SCOPE_LABELS[scope]}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

function RuleDetails({
  module,
  action,
  rule,
  onChange,
  attributeSuggestions,
}: {
  module: ModuleDefinition;
  action: RuleAction;
  rule: RuleDraft;
  onChange: (rule: RuleDraft) => void;
  attributeSuggestions: string[];
}) {
  const filterable = activeFields(module).filter((f) => f.filterable);
  const listId = React.useId();
  if (rule.scope === "NONE") return null;
  const actionLabel = action === "read" ? "Read" : action === "update" ? "Update" : "Delete";
  return (
    <div className="grid gap-2 rounded-md border border-border bg-background p-2.5">
      <p className="text-xs font-medium">
        {actionLabel}: <span className="font-normal text-muted-foreground">{SCOPE_HINTS[rule.scope]}</span>
      </p>
      {rule.scope === "ATTRIBUTE" ? (
        <div className="grid gap-2 sm:grid-cols-2">
          <div className="grid gap-1">
            <Label className="text-xs" htmlFor={`${listId}-field`}>
              Record field
            </Label>
            <Select value={rule.field} onValueChange={(field) => onChange({ ...rule, field })}>
              <SelectTrigger id={`${listId}-field`} size="sm" className="h-7 text-xs">
                <SelectValue placeholder="Pick a field" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={RECORD_ID_FIELD}>Record ID ($id)</SelectItem>
                {filterable.map((field) => (
                  <SelectItem key={field.apiKey} value={field.apiKey}>
                    {field.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="grid gap-1">
            <Label className="text-xs" htmlFor={`${listId}-attr`}>
              equals member attribute
            </Label>
            <Input
              id={`${listId}-attr`}
              list={`${listId}-attrs`}
              value={rule.attribute}
              placeholder="e.g. department"
              className="h-7 font-mono text-xs"
              onChange={(event) => onChange({ ...rule, attribute: event.target.value.trim() })}
            />
            <datalist id={`${listId}-attrs`}>
              {attributeSuggestions.map((name) => (
                <option key={name} value={name} />
              ))}
            </datalist>
          </div>
        </div>
      ) : null}
      <div className="grid gap-1.5">
        <p className="text-xs text-muted-foreground">Only where (optional)</p>
        {rule.where.map((clause, index) => {
          const field = filterable.find((f) => f.apiKey === clause.field);
          const options = field && (field.type === "SINGLE_SELECT" || field.type === "MULTI_SELECT") ? selectOptions(field) : null;
          const update = (patch: Partial<typeof clause>) =>
            onChange({ ...rule, where: rule.where.map((c, i) => (i === index ? { ...c, ...patch } : c)) });
          return (
            <div key={index} className="grid grid-cols-[minmax(0,9rem)_minmax(0,1fr)_auto] items-start gap-1.5">
              <Select value={clause.field} onValueChange={(f) => update({ field: f, values: [] })}>
                <SelectTrigger size="sm" className="h-7 text-xs" aria-label="Condition field">
                  <SelectValue placeholder="Field" />
                </SelectTrigger>
                <SelectContent>
                  {filterable.map((f) => (
                    <SelectItem key={f.apiKey} value={f.apiKey}>
                      {f.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {options ? (
                <MultiCombobox
                  aria-label="Allowed values"
                  value={clause.values}
                  onChange={(values) => update({ values })}
                  options={options.map((o) => ({ value: o.value, label: o.label }))}
                  placeholder="is any of…"
                  className="min-h-7 text-xs"
                />
              ) : (
                <Input
                  aria-label="Allowed values (comma separated)"
                  className="h-7 text-xs"
                  placeholder="value, value…"
                  value={clause.values.join(", ")}
                  onChange={(event) =>
                    update({ values: event.target.value.split(",").map((v) => v.trim()).filter(Boolean) })
                  }
                />
              )}
              <Button
                variant="ghost"
                size="icon-xs"
                aria-label="Remove condition"
                onClick={() => onChange({ ...rule, where: rule.where.filter((_, i) => i !== index) })}
              >
                <X />
              </Button>
            </div>
          );
        })}
        <Button
          variant="ghost"
          size="xs"
          className="self-start text-muted-foreground"
          disabled={filterable.length === 0}
          onClick={() => onChange({ ...rule, where: [...rule.where, { field: "", values: [] }] })}
        >
          <Plus /> Condition
        </Button>
      </div>
    </div>
  );
}

function ModuleRow({
  module,
  draft,
  onChange,
  errors,
  attributeSuggestions,
}: {
  module: ModuleDefinition;
  draft: ModuleDraft;
  onChange: (draft: ModuleDraft) => void;
  errors: string[];
  attributeSuggestions: string[];
}) {
  const [open, setOpen] = React.useState(false);
  const fields = activeFields(module);
  const fieldOptions = fields.map((f) => ({ value: f.apiKey, label: f.label, description: f.apiKey }));
  const advanced =
    RULE_ACTIONS.some((a) => draft[a].scope === "ATTRIBUTE" || draft[a].where.length > 0) ||
    draft.hiddenFields.length > 0 ||
    draft.readOnlyFields.length > 0;
  const expanded = open || errors.length > 0;
  const setRule = (action: RuleAction, rule: RuleDraft) => {
    onChange({ ...draft, [action]: rule });
    if (rule.scope === "ATTRIBUTE") setOpen(true);
  };
  const granted = draft.read.scope !== "NONE" || draft.create || draft.update.scope !== "NONE" || draft.delete.scope !== "NONE";
  const detailsId = `role-row-${module.apiKey}`;

  return (
    <>
      <tr className={cn("border-b border-border", !granted && "text-muted-foreground")}>
        <th scope="row" className="py-2 pl-3 pr-2 text-left font-normal">
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            aria-expanded={expanded}
            aria-controls={detailsId}
            className="focus-ring flex w-full min-w-0 items-center gap-2 rounded-sm text-left"
          >
            {expanded ? <ChevronDown className="size-3.5 shrink-0" /> : <ChevronRight className="size-3.5 shrink-0" />}
            <ModuleGlyph moduleKey={module.apiKey} className="size-4 shrink-0 text-muted-foreground" />
            <span className={cn("truncate text-[13px]", granted && "font-medium text-foreground")}>{module.labelPlural}</span>
            {advanced ? <Badge variant="outline" className="ml-1">rules</Badge> : null}
            {errors.length > 0 ? <Badge variant="destructive">{errors.length}</Badge> : null}
          </button>
        </th>
        <td className="px-1.5 py-2">
          <ScopeSelect label={`${module.labelPlural}: read`} value={draft.read.scope} onChange={(scope) => setRule("read", { ...draft.read, scope })} />
        </td>
        <td className="px-1.5 py-2 text-center">
          <Checkbox
            aria-label={`${module.labelPlural}: create`}
            checked={draft.create}
            onCheckedChange={(checked) => onChange({ ...draft, create: checked === true })}
          />
        </td>
        <td className="px-1.5 py-2">
          <ScopeSelect label={`${module.labelPlural}: update`} value={draft.update.scope} onChange={(scope) => setRule("update", { ...draft.update, scope })} />
        </td>
        <td className="px-1.5 py-2">
          <ScopeSelect label={`${module.labelPlural}: delete`} value={draft.delete.scope} onChange={(scope) => setRule("delete", { ...draft.delete, scope })} />
        </td>
        <td className="px-3 py-2 text-xs text-muted-foreground">
          {draft.hiddenFields.length || draft.readOnlyFields.length ? (
            <span>
              {draft.hiddenFields.length ? `${draft.hiddenFields.length} hidden` : null}
              {draft.hiddenFields.length && draft.readOnlyFields.length ? " · " : null}
              {draft.readOnlyFields.length ? `${draft.readOnlyFields.length} read-only` : null}
            </span>
          ) : (
            "All"
          )}
        </td>
      </tr>
      {expanded ? (
        <tr id={detailsId} className="border-b border-border bg-surface">
          <td colSpan={6} className="p-3">
            {errors.length > 0 ? (
              <ul className="mb-2 list-disc pl-4 text-xs text-destructive">
                {errors.map((message, i) => (
                  <li key={i}>{message}</li>
                ))}
              </ul>
            ) : null}
            <div className="grid gap-3 lg:grid-cols-3">
              {RULE_ACTIONS.map((action) => (
                <RuleDetails
                  key={action}
                  module={module}
                  action={action}
                  rule={draft[action]}
                  onChange={(rule) => setRule(action, rule)}
                  attributeSuggestions={attributeSuggestions}
                />
              ))}
            </div>
            <div className="mt-3 grid gap-3 sm:grid-cols-2">
              <div className="grid gap-1">
                <Label className="text-xs" htmlFor={`${detailsId}-hidden`}>
                  Hidden fields
                </Label>
                <MultiCombobox
                  id={`${detailsId}-hidden`}
                  value={draft.hiddenFields}
                  onChange={(hiddenFields) =>
                    onChange({ ...draft, hiddenFields, readOnlyFields: draft.readOnlyFields.filter((f) => !hiddenFields.includes(f)) })
                  }
                  options={fieldOptions}
                  placeholder="None hidden"
                />
                <FieldMessage hint="Never sent to members with this role; they can't filter or search on them either." />
              </div>
              <div className="grid gap-1">
                <Label className="text-xs" htmlFor={`${detailsId}-readonly`}>
                  Read-only fields
                </Label>
                <MultiCombobox
                  id={`${detailsId}-readonly`}
                  value={draft.readOnlyFields}
                  onChange={(readOnlyFields) => onChange({ ...draft, readOnlyFields })}
                  options={fieldOptions.filter((o) => !draft.hiddenFields.includes(o.value))}
                  placeholder="None read-only"
                />
                <FieldMessage hint="Visible but can't be changed by this role." />
              </div>
            </div>
            {RULE_ACTIONS.every((a) => draft[a].scope === "NONE") ? (
              <p className="mt-2 text-xs text-muted-foreground">Pick a scope for Read, Update or Delete to add record rules.</p>
            ) : null}
          </td>
        </tr>
      ) : null}
    </>
  );
}

function PermissionMatrix({
  modules,
  drafts,
  onChange,
  errors,
  attributeSuggestions,
}: {
  modules: ModuleDefinition[];
  drafts: Record<string, ModuleDraft>;
  onChange: (key: string, draft: ModuleDraft) => void;
  errors: Map<string, string[]>;
  attributeSuggestions: string[];
}) {
  return (
    <div className="overflow-x-auto rounded-lg border border-border bg-card">
      <table className="w-full min-w-[46rem] text-[13px]" aria-label="Module permissions">
        <thead>
          <tr className="border-b border-border bg-surface text-left text-xs text-muted-foreground">
            <th scope="col" className="w-[30%] py-2 pl-3 pr-2 font-medium">Module</th>
            <th scope="col" className="w-[15%] px-1.5 py-2 font-medium">Read</th>
            <th scope="col" className="w-[8%] px-1.5 py-2 text-center font-medium">Create</th>
            <th scope="col" className="w-[15%] px-1.5 py-2 font-medium">Update</th>
            <th scope="col" className="w-[15%] px-1.5 py-2 font-medium">Delete</th>
            <th scope="col" className="px-3 py-2 font-medium">Fields</th>
          </tr>
        </thead>
        <tbody>
          {modules.map((module) => (
            <ModuleRow
              key={module.apiKey}
              module={module}
              draft={drafts[module.apiKey] ?? emptyModuleDraft()}
              onChange={(draft) => onChange(module.apiKey, draft)}
              errors={violationsUnder(errors, `permissions.modules.${module.apiKey}`)}
              attributeSuggestions={attributeSuggestions}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* ---------------------------------------------------------------- editor */

function RoleEditor({ role }: { role: OrgRole | null }) {
  const router = useRouter();
  const { orgId, modules } = useOrg();
  const create = useCreateRole(orgId);
  const update = useUpdateRole(orgId);
  const remove = useDeleteRole(orgId);
  const [label, setLabel] = React.useState(role?.label ?? "");
  const [apiKey, setApiKey] = React.useState(role?.apiKey ?? "");
  const [keyEdited, setKeyEdited] = React.useState(Boolean(role));
  const [description, setDescription] = React.useState(role?.description ?? "");
  const [drafts, setDrafts] = React.useState<Record<string, ModuleDraft>>(() => fromDocument(role?.permissions));
  const [view, setView] = React.useState<"matrix" | "json">("matrix");
  const [jsonText, setJsonText] = React.useState("");
  const [jsonError, setJsonError] = React.useState<string | null>(null);
  const [errors, setErrors] = React.useState<Map<string, string[]>>(new Map());
  const [deleteOpen, setDeleteOpen] = React.useState(false);
  const [deleteError, setDeleteError] = React.useState<string | null>(null);
  const saving = create.isPending || update.isPending;

  const extraKeys = Object.keys(drafts).filter((key) => !modules.some((m) => m.apiKey === key));
  const attributeSuggestions = React.useMemo(() => {
    const names = new Set<string>(["department", "company", "student", "batch"]);
    Object.values(drafts).forEach((d) => RULE_ACTIONS.forEach((a) => d[a].attribute && names.add(d[a].attribute)));
    return [...names];
  }, [drafts]);

  const switchView = (next: string) => {
    if (next === "json") {
      setJsonText(JSON.stringify(toDocument(drafts), null, 2));
      setJsonError(null);
      setView("json");
      return;
    }
    const parsed = parsePermissionJson(jsonText);
    if ("error" in parsed) {
      setJsonError(parsed.error);
      return;
    }
    setDrafts(fromDocument(parsed.document));
    setView("matrix");
  };

  const currentDocument = () => {
    if (view === "json") {
      const parsed = parsePermissionJson(jsonText);
      if ("error" in parsed) {
        setJsonError(parsed.error);
        return null;
      }
      return parsed.document;
    }
    return toDocument(drafts);
  };

  const save = async () => {
    const permissions = currentDocument();
    if (!permissions) return;
    const local = new Map<string, string[]>();
    if (!label.trim()) local.set("label", ["Enter a name"]);
    if (!role && !API_KEY_PATTERN.test(apiKey)) local.set("apiKey", ["Use lowercase letters, digits and underscores, starting with a letter"]);
    if (local.size) {
      setErrors(local);
      return;
    }
    setErrors(new Map());
    const input = { label: label.trim(), description: description.trim() || null, permissions };
    try {
      if (role) {
        await update.mutateAsync({ roleKey: role.apiKey, version: role.version, input });
        toast.success(`${input.label} saved`);
      } else {
        const created = await create.mutateAsync({ ...input, apiKey });
        toast.success(`${created.label} created`);
        router.replace(adminPath(orgId, "roles", `/${created.apiKey}`));
      }
    } catch (error) {
      if (error instanceof ApiError && error.isValidation) {
        setErrors(violationMap(error));
        toast.error("Some permissions are invalid", { description: "See the highlighted modules." });
      } else if (error instanceof ApiError && error.isVersionConflict) {
        toast.error("Someone else changed this role", { description: "Reload the page to get the latest version." });
      } else if (error instanceof ApiError && error.code === "role_exists") {
        setErrors(new Map([["apiKey", [error.friendlyMessage]]]));
      }
    }
  };

  const confirmDelete = async () => {
    if (!role) return;
    setDeleteError(null);
    try {
      await remove.mutateAsync({ roleKey: role.apiKey, version: role.version });
      toast.success(`${role.label} deleted`);
      setDeleteOpen(false);
      router.replace(adminPath(orgId, "roles"));
    } catch (error) {
      setDeleteError(errorMessage(error));
    }
  };

  const jsonErrors = [...errors.entries()].filter(([path]) => path.startsWith("permissions"));

  return (
    <>
      <PageHeader
        title={role ? role.label : "New role"}
        description={role ? <code className="font-mono text-xs">{role.apiKey}</code> : "Name the role, then decide what it can do in each module."}
        actions={
          <>
            {role ? (
              <Button variant="destructive-outline" onClick={() => setDeleteOpen(true)}>
                <Trash2 /> Delete
              </Button>
            ) : null}
            <Button onClick={() => void save()} disabled={saving}>
              {saving ? <Spinner className="text-primary-foreground" /> : <Save />}
              {role ? "Save role" : "Create role"}
            </Button>
          </>
        }
      />
      <PageBody className="flex flex-col gap-6">
        <section className="grid gap-4 rounded-lg border border-border bg-card p-4 md:grid-cols-[1fr_1fr_2fr]">
          <div className="grid gap-1.5">
            <Label htmlFor="role-label">Name</Label>
            <Input
              id="role-label"
              value={label}
              placeholder="Department coordinator"
              aria-invalid={Boolean(errors.get("label")) || undefined}
              onChange={(event) => {
                setLabel(event.target.value);
                if (!keyEdited) setApiKey(toApiKey(event.target.value));
              }}
            />
            <FieldMessage error={errors.get("label")?.join(" ")} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="role-key">Key</Label>
            <Input
              id="role-key"
              value={apiKey}
              disabled={Boolean(role)}
              className="font-mono text-[13px]"
              aria-invalid={Boolean(errors.get("apiKey")) || undefined}
              onChange={(event) => {
                setApiKey(event.target.value);
                setKeyEdited(true);
              }}
            />
            <FieldMessage error={errors.get("apiKey")?.join(" ")} hint={role ? "Keys can't change." : "Used in workflows and invitations."} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="role-description">Description</Label>
            <Input
              id="role-description"
              value={description}
              placeholder="Who gets this role and why"
              onChange={(event) => setDescription(event.target.value)}
            />
            <FieldMessage error={errors.get("description")?.join(" ")} />
          </div>
        </section>

        <section aria-labelledby="perm-heading" className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 id="perm-heading" className="text-sm font-semibold">
                Permissions
              </h2>
              <p className="text-[13px] text-muted-foreground">
                Read, Update and Delete pick which records: all, the member&apos;s own, or those matching a member attribute (for
                example a coordinator&apos;s department). Expand a module for conditions and field rules.
              </p>
            </div>
            <ViewSwitch value={view} onChange={switchView} />
          </div>
          {view === "matrix" ? (
            <>
              <PermissionMatrix
                modules={modules}
                drafts={drafts}
                onChange={(key, draft) => setDrafts((current) => ({ ...current, [key]: draft }))}
                errors={errors}
                attributeSuggestions={attributeSuggestions}
              />
              {extraKeys.length > 0 ? (
                <p className="text-xs text-muted-foreground">
                  Also configured for archived or unknown modules: {extraKeys.join(", ")} (edit them in the JSON view).
                </p>
              ) : null}
              {RULE_ACTIONS.some((a) => Object.values(drafts).some((d) => d[a].scope === "ATTRIBUTE")) ? (
                <p className="text-xs text-muted-foreground">
                  Attribute rules compare with member attributes set on the Members page; members without the attribute see no records.
                </p>
              ) : null}
            </>
          ) : (
            <div className="grid gap-2">
              <Label htmlFor="role-json" className="sr-only">
                Permission document (JSON)
              </Label>
              <Textarea
                id="role-json"
                value={jsonText}
                spellCheck={false}
                className="min-h-[28rem] font-mono text-xs leading-relaxed"
                aria-invalid={Boolean(jsonError) || undefined}
                aria-describedby="role-json-help"
                onChange={(event) => {
                  setJsonText(event.target.value);
                  setJsonError(null);
                }}
              />
              <FieldMessage
                id="role-json-help"
                error={jsonError}
                hint={'{"modules": {"student": {"read": "OWN", "update": {"scope": "ATTRIBUTE", "field": "department", "attribute": "department"}, "readOnlyFields": ["cgpa"]}}}'}
              />
              {jsonErrors.length > 0 ? (
                <ul className="list-disc rounded-md border border-destructive/20 bg-destructive-soft py-2 pl-7 pr-3 text-xs text-destructive-soft-foreground">
                  {jsonErrors.map(([path, messages]) => (
                    <li key={path}>
                      <code className="font-mono">{path}</code>: {messages.join(" ")}
                    </li>
                  ))}
                </ul>
              ) : null}
            </div>
          )}
        </section>
      </PageBody>

      <AlertDialog open={deleteOpen} onOpenChange={(open) => {
        setDeleteOpen(open);
        if (!open) setDeleteError(null);
      }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete {role?.label}?</AlertDialogTitle>
            <AlertDialogDescription>Workflows that list this role keep the key but nobody will hold it.</AlertDialogDescription>
          </AlertDialogHeader>
          {deleteError ? (
            <div role="alert" className="rounded-md border border-destructive/20 bg-destructive-soft px-3 py-2 text-[13px] text-destructive-soft-foreground">
              {deleteError}{" "}
              <Link href={adminPath(orgId, "members")} className="font-medium underline">
                Open members
              </Link>
            </div>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              disabled={remove.isPending}
              onClick={(event) => {
                event.preventDefault();
                void confirmDelete();
              }}
            >
              {remove.isPending ? <Spinner className="text-destructive-foreground" /> : null}
              Delete role
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}

function ViewSwitch({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return (
    <Tabs value={value} onValueChange={onChange}>
      <SegmentedList aria-label="Editor view">
        <SegmentedTrigger value="matrix">
          <Table2 /> Matrix
        </SegmentedTrigger>
        <SegmentedTrigger value="json">
          <Braces /> JSON
        </SegmentedTrigger>
      </SegmentedList>
    </Tabs>
  );
}

export function RoleEditorPage({ roleKey }: { roleKey: string }) {
  const { orgId } = useOrg();
  const roles = useRoles(orgId, { enabled: roleKey !== "new" });
  const role = roles.data?.find((r) => r.apiKey === roleKey) ?? null;
  return (
    <AdminGate>
      {roleKey === "new" ? (
        <RoleEditor role={null} />
      ) : roles.isLoading ? (
        <div className="flex flex-col gap-4 px-4 pt-6 sm:px-6 lg:px-8">
          <Skeleton className="h-6 w-48" />
          <Skeleton className="h-24 w-full rounded-lg" />
          <Skeleton className="h-96 w-full rounded-lg" />
        </div>
      ) : roles.isError ? (
        <ErrorState error={roles.error} onRetry={() => void roles.refetch()} />
      ) : role ? (
        <RoleEditor key={role.version} role={role} />
      ) : (
        <EmptyState
          icon={ShieldCheck}
          title="Role not found"
          description="It may have been deleted."
          action={
            <Button variant="outline" asChild>
              <Link href={adminPath(orgId, "roles")}>All roles</Link>
            </Button>
          }
        />
      )}
    </AdminGate>
  );
}
