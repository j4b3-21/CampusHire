"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { toast } from "sonner";
import { Archive, ArchiveRestore, Boxes, ChevronRight, History, LayoutTemplate, ListPlus, MoreHorizontal, Pencil, Plus, Rows3, Save, Settings2 } from "lucide-react";

import { AdminGate, API_KEY_PATTERN, FieldMessage, toApiKey } from "@/components/admin/admin-gate";
import { FieldDialog, FIELD_TYPE_LABELS } from "@/components/admin/field-dialog";
import { LayoutBuilder } from "@/components/admin/layout-builder";
import { useOrg } from "@/components/app/org-context";
import { ErrorState } from "@/components/common/error-state";
import { ModuleGlyph } from "@/components/common/module-glyph";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Pagination } from "@/components/ui/pagination";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { SimpleTooltip } from "@/components/ui/tooltip";
import {
  ApiError,
  useAllModules,
  useCreateModule,
  useMembers,
  useModuleVersions,
  useSetFieldStatus,
  useSetModuleStatus,
  useUpdateModule,
  type FieldStatusAction,
} from "@/lib/api";
import { violationMap } from "@/lib/api/violations";
import { formatDateTime, formatRelative, shortId } from "@/lib/metadata/format";
import { adminPath, modulePath } from "@/lib/navigation";
import { cn } from "@/lib/utils";
import type { JsonValue } from "@/types/api";
import type { FieldDefinition, ModuleDefinition, ModuleSettings } from "@/types/metadata";

const NONE = "__none";

/* ---------------------------------------------------------------- index */

function CreateModuleDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const router = useRouter();
  const { orgId } = useOrg();
  const create = useCreateModule(orgId);
  const [singular, setSingular] = React.useState("");
  const [plural, setPlural] = React.useState("");
  const [apiKey, setApiKey] = React.useState("");
  const [keyEdited, setKeyEdited] = React.useState(false);
  const [description, setDescription] = React.useState("");
  const [accessMode, setAccessMode] = React.useState<"MEMBERS" | "ROLES">("ROLES");
  const [errors, setErrors] = React.useState<Map<string, string[]>>(new Map());
  const err = (path: string) => errors.get(path)?.join(" ");

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const local = new Map<string, string[]>();
    if (!singular.trim()) local.set("labelSingular", ["Enter a name"]);
    if (!plural.trim()) local.set("labelPlural", ["Enter the plural name"]);
    if (!API_KEY_PATTERN.test(apiKey)) local.set("apiKey", ["Lowercase letters, digits and underscores, starting with a letter"]);
    setErrors(local);
    if (local.size) return;
    try {
      const created = await create.mutateAsync({
        apiKey,
        labelSingular: singular.trim(),
        labelPlural: plural.trim(),
        description: description.trim() || null,
        settings: { accessMode, titleField: "name" },
        fields: [{ apiKey: "name", label: "Name", type: "TEXT", required: true, searchable: true, sortable: true, filterable: true }],
      });
      toast.success(`${created.labelPlural} created`);
      onOpenChange(false);
      router.push(adminPath(orgId, "modules", `/${created.apiKey}`));
    } catch (error) {
      if (error instanceof ApiError && error.isValidation) setErrors(violationMap(error));
      else if (error instanceof ApiError && error.code === "module_exists") setErrors(new Map([["apiKey", [error.friendlyMessage]]]));
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form onSubmit={submit} className="grid gap-4" noValidate>
          <DialogHeader>
            <DialogTitle>New module</DialogTitle>
            <DialogDescription>Starts with a required Name field; add more fields afterwards.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="grid gap-1.5">
              <Label htmlFor="mod-singular">Name (singular)</Label>
              <Input
                id="mod-singular"
                autoFocus
                value={singular}
                placeholder="Workshop"
                aria-invalid={Boolean(err("labelSingular")) || undefined}
                onChange={(event) => {
                  setSingular(event.target.value);
                  if (!keyEdited) setApiKey(toApiKey(event.target.value));
                  if (!plural || plural === `${singular}s`) setPlural(event.target.value ? `${event.target.value}s` : "");
                }}
              />
              <FieldMessage error={err("labelSingular")} />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="mod-plural">Name (plural)</Label>
              <Input id="mod-plural" value={plural} placeholder="Workshops" aria-invalid={Boolean(err("labelPlural")) || undefined} onChange={(event) => setPlural(event.target.value)} />
              <FieldMessage error={err("labelPlural")} />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="mod-key">Key</Label>
              <Input
                id="mod-key"
                value={apiKey}
                className="font-mono text-[13px]"
                aria-invalid={Boolean(err("apiKey")) || undefined}
                onChange={(event) => {
                  setApiKey(event.target.value);
                  setKeyEdited(true);
                }}
              />
              <FieldMessage error={err("apiKey")} hint="Can't change later." />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="mod-access">Who can use it</Label>
              <Select value={accessMode} onValueChange={(v) => setAccessMode(v as "MEMBERS" | "ROLES")}>
                <SelectTrigger id="mod-access">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ROLES">Roles that list it</SelectItem>
                  <SelectItem value="MEMBERS">Every member</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="mod-description">Description</Label>
            <Textarea id="mod-description" value={description} onChange={(event) => setDescription(event.target.value)} className="min-h-16" />
          </div>
          {[...errors.entries()].filter(([p]) => !["labelSingular", "labelPlural", "apiKey"].includes(p)).map(([path, messages]) => (
            <FieldMessage key={path} error={`${path}: ${messages.join(" ")}`} />
          ))}
          <DialogFooter>
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? <Spinner className="text-primary-foreground" /> : null}
              Create module
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export function ModulesIndexPage() {
  const { orgId } = useOrg();
  const modules = useAllModules(orgId);
  const [creating, setCreating] = React.useState(false);
  const list = [...(modules.data ?? [])].sort((a, b) => Number(a.status === "ARCHIVED") - Number(b.status === "ARCHIVED"));
  return (
    <AdminGate>
      <PageHeader
        title="Customize modules"
        description="Fields, form layouts and pick-list options for every kind of record. Changes apply immediately and are versioned."
        actions={
          <Button onClick={() => setCreating(true)}>
            <Plus /> New module
          </Button>
        }
      />
      <PageBody>
        {modules.isLoading ? (
          <div className="flex flex-col gap-2">
            {Array.from({ length: 6 }, (_, i) => (
              <Skeleton key={i} className="h-14 rounded-lg" />
            ))}
          </div>
        ) : modules.isError ? (
          <ErrorState error={modules.error} onRetry={() => void modules.refetch()} />
        ) : list.length === 0 ? (
          <EmptyState icon={Boxes} title="No modules yet" description="Install CampusHire from the dashboard, or create a module." />
        ) : (
          <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border bg-card">
            {list.map((module) => {
              const archived = module.status === "ARCHIVED";
              const fieldCount = module.fields.filter((f) => f.status !== "ARCHIVED").length;
              return (
                <li key={module.apiKey}>
                  <Link
                    href={adminPath(orgId, "modules", `/${module.apiKey}`)}
                    className={cn("focus-ring flex items-center gap-3 px-4 py-3 transition-colors hover:bg-surface", archived && "opacity-60")}
                  >
                    <span className="grid size-8 shrink-0 place-items-center rounded-md border border-border bg-background text-muted-foreground">
                      <ModuleGlyph moduleKey={module.apiKey} className="size-4" />
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="flex items-center gap-2 truncate text-[13px] font-medium">
                        {module.labelPlural}
                        {archived ? <Badge variant="neutral">Archived</Badge> : null}
                        {module.scope === "GLOBAL" ? <Badge variant="outline">Platform</Badge> : null}
                      </p>
                      <p className="truncate text-xs text-muted-foreground">
                        <code className="font-mono">{module.apiKey}</code> · {fieldCount} fields · v{module.version}
                      </p>
                    </div>
                    <span className="hidden text-xs text-subtle-foreground sm:inline">updated {formatRelative(module.updatedAt)}</span>
                    <ChevronRight className="size-4 shrink-0 text-subtle-foreground" />
                  </Link>
                </li>
              );
            })}
          </ul>
        )}
      </PageBody>
      <CreateModuleDialog open={creating} onOpenChange={setCreating} />
    </AdminGate>
  );
}

/* ---------------------------------------------------------------- fields tab */

const STATUS_BADGE: Record<string, "success" | "warning" | "neutral"> = { ACTIVE: "success", DEPRECATED: "warning", ARCHIVED: "neutral" };

function FieldFlags({ field }: { field: FieldDefinition }) {
  const flags = [
    field.required && "Required",
    field.unique && "Unique",
    field.searchable && "Search",
    field.filterable && "Filter",
    field.sortable && "Sort",
    field.sensitive && "Sensitive",
    field.readOnly && "Read-only",
  ].filter(Boolean) as string[];
  return (
    <span className="flex flex-wrap gap-1">
      {flags.map((flag) => (
        <Badge key={flag} variant="outline" className="px-1 text-[11px]">
          {flag}
        </Badge>
      ))}
    </span>
  );
}

function FieldsTab({ module }: { module: ModuleDefinition }) {
  const { orgId } = useOrg();
  const setStatus = useSetFieldStatus(orgId, module.apiKey);
  const [editing, setEditing] = React.useState<FieldDefinition | null | "new">(null);
  const [showArchived, setShowArchived] = React.useState(false);
  const readOnly = module.scope === "GLOBAL";
  const fields = [...module.fields]
    .filter((f) => showArchived || f.status !== "ARCHIVED")
    .sort((a, b) => a.position - b.position);
  const archivedCount = module.fields.filter((f) => f.status === "ARCHIVED").length;

  const changeStatus = async (field: FieldDefinition, action: FieldStatusAction) => {
    try {
      await setStatus.mutateAsync({ fieldKey: field.apiKey, version: field.version, action });
      toast.success(`${field.label} ${action === "activate" ? "activated" : action === "archive" ? "archived" : "deprecated"}`);
    } catch {
      /* toasted */
    }
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button onClick={() => setEditing("new")} disabled={readOnly}>
          <ListPlus /> Add field
        </Button>
        {archivedCount > 0 ? (
          <Button variant="ghost" size="sm" onClick={() => setShowArchived((v) => !v)}>
            {showArchived ? "Hide" : "Show"} {archivedCount} archived
          </Button>
        ) : null}
        <p className="ml-auto text-xs text-muted-foreground">Deprecated fields stay readable but can&apos;t be edited; archived fields disappear.</p>
      </div>
      <div className="overflow-x-auto rounded-lg border border-border bg-card">
        <Table aria-label={`${module.labelPlural} fields`}>
          <TableHeader>
            <tr>
              <TableHead>Field</TableHead>
              <TableHead>Type</TableHead>
              <TableHead>Options</TableHead>
              <TableHead>Status</TableHead>
              <TableHead className="w-10">
                <span className="sr-only">Actions</span>
              </TableHead>
            </tr>
          </TableHeader>
          <TableBody>
            {fields.map((field) => (
              <TableRow key={field.apiKey} className={cn(field.status === "ARCHIVED" && "opacity-60")}>
                <TableCell className="h-12">
                  <button
                    type="button"
                    disabled={readOnly || field.status === "ARCHIVED"}
                    onClick={() => setEditing(field)}
                    className="focus-ring rounded-sm text-left font-medium hover:underline disabled:no-underline"
                  >
                    {field.label}
                  </button>
                  <p className="font-mono text-[11px] text-muted-foreground">{field.apiKey}</p>
                </TableCell>
                <TableCell className="whitespace-nowrap">
                  {FIELD_TYPE_LABELS[field.type]}
                  {field.type === "LOOKUP" && field.config?.targetModuleKey ? (
                    <span className="text-muted-foreground"> → {field.config.targetModuleKey}</span>
                  ) : null}
                  {(field.type === "SINGLE_SELECT" || field.type === "MULTI_SELECT") && field.config?.options ? (
                    <span className="text-muted-foreground"> · {field.config.options.length}</span>
                  ) : null}
                </TableCell>
                <TableCell>
                  <FieldFlags field={field} />
                </TableCell>
                <TableCell>
                  <Badge variant={STATUS_BADGE[field.status] ?? "neutral"}>{field.status.charAt(0) + field.status.slice(1).toLowerCase()}</Badge>
                </TableCell>
                <TableCell>
                  {readOnly ? null : (
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button variant="ghost" size="icon-xs" aria-label={`Actions for ${field.label}`}>
                          <MoreHorizontal />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end">
                        <DropdownMenuItem disabled={field.status === "ARCHIVED"} onSelect={() => setEditing(field)}>
                          <Pencil /> Edit
                        </DropdownMenuItem>
                        <DropdownMenuSeparator />
                        {field.status !== "ACTIVE" ? (
                          <DropdownMenuItem onSelect={() => void changeStatus(field, "activate")}>
                            <ArchiveRestore /> Activate
                          </DropdownMenuItem>
                        ) : null}
                        {field.status === "ACTIVE" ? (
                          <DropdownMenuItem onSelect={() => void changeStatus(field, "deprecate")}>
                            <Rows3 /> Deprecate
                          </DropdownMenuItem>
                        ) : null}
                        {field.status !== "ARCHIVED" ? (
                          <DropdownMenuItem variant="destructive" onSelect={() => void changeStatus(field, "archive")}>
                            <Archive /> Archive
                          </DropdownMenuItem>
                        ) : null}
                      </DropdownMenuContent>
                    </DropdownMenu>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {editing ? (
        <FieldDialog
          key={editing === "new" ? "new" : `${editing.apiKey}-${editing.version}`}
          module={module}
          field={editing === "new" ? null : editing}
          open
          onOpenChange={(open) => !open && setEditing(null)}
        />
      ) : null}
    </div>
  );
}

/* ---------------------------------------------------------------- settings tab */

function SettingsTab({ module }: { module: ModuleDefinition }) {
  const { orgId } = useOrg();
  const update = useUpdateModule(orgId, module.apiKey);
  const setStatus = useSetModuleStatus(orgId, module.apiKey);
  const settings: ModuleSettings = module.settings ?? {};
  const [singular, setSingular] = React.useState(module.labelSingular);
  const [plural, setPlural] = React.useState(module.labelPlural);
  const [description, setDescription] = React.useState(module.description ?? "");
  const [accessMode, setAccessMode] = React.useState<string>(settings.accessMode ?? "MEMBERS");
  const [titleField, setTitleField] = React.useState<string>(typeof settings.titleField === "string" ? settings.titleField : NONE);
  const [subtitleField, setSubtitleField] = React.useState<string>(typeof settings.subtitleField === "string" ? settings.subtitleField : NONE);
  const [errors, setErrors] = React.useState<Map<string, string[]>>(new Map());
  const fields = module.fields.filter((f) => f.status !== "ARCHIVED");
  const archived = module.status === "ARCHIVED";

  const save = async () => {
    const next: Record<string, JsonValue> = { ...settings, accessMode };
    if (titleField === NONE) delete next.titleField;
    else next.titleField = titleField;
    if (subtitleField === NONE) delete next.subtitleField;
    else next.subtitleField = subtitleField;
    try {
      await update.mutateAsync({
        version: module.version,
        input: { labelSingular: singular.trim(), labelPlural: plural.trim(), description: description.trim() || null, settings: next },
      });
      setErrors(new Map());
      toast.success("Module saved");
    } catch (error) {
      if (error instanceof ApiError && error.isValidation) setErrors(violationMap(error));
    }
  };

  const err = (path: string) => errors.get(path)?.join(" ");

  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <section className="grid gap-4 rounded-lg border border-border bg-card p-4 sm:grid-cols-2">
        <div className="grid gap-1.5">
          <Label htmlFor="set-singular">Name (singular)</Label>
          <Input id="set-singular" value={singular} onChange={(e) => setSingular(e.target.value)} aria-invalid={Boolean(err("labelSingular")) || undefined} />
          <FieldMessage error={err("labelSingular")} />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="set-plural">Name (plural)</Label>
          <Input id="set-plural" value={plural} onChange={(e) => setPlural(e.target.value)} aria-invalid={Boolean(err("labelPlural")) || undefined} />
          <FieldMessage error={err("labelPlural")} />
        </div>
        <div className="grid gap-1.5 sm:col-span-2">
          <Label htmlFor="set-description">Description</Label>
          <Textarea id="set-description" value={description} onChange={(e) => setDescription(e.target.value)} className="min-h-16" />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="set-access">Who can use it</Label>
          <Select value={accessMode} onValueChange={setAccessMode}>
            <SelectTrigger id="set-access">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ROLES">Only roles that list it</SelectItem>
              <SelectItem value="MEMBERS">Every member</SelectItem>
            </SelectContent>
          </Select>
          <FieldMessage error={err("settings.accessMode")} hint="Owners and admins can always use every module." />
        </div>
        <div />
        <div className="grid gap-1.5">
          <Label htmlFor="set-title">Title field</Label>
          <Select value={titleField} onValueChange={setTitleField}>
            <SelectTrigger id="set-title">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={NONE}>Automatic</SelectItem>
              {fields.map((f) => (
                <SelectItem key={f.apiKey} value={f.apiKey}>
                  {f.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <FieldMessage hint="Names records in lists, boards and lookups." />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="set-subtitle">Subtitle field</Label>
          <Select value={subtitleField} onValueChange={setSubtitleField}>
            <SelectTrigger id="set-subtitle">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={NONE}>None</SelectItem>
              {fields.map((f) => (
                <SelectItem key={f.apiKey} value={f.apiKey}>
                  {f.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <FieldMessage hint="Shown under the title on board cards." />
        </div>
        <div className="flex justify-end sm:col-span-2">
          <Button onClick={() => void save()} disabled={update.isPending}>
            {update.isPending ? <Spinner className="text-primary-foreground" /> : <Save />} Save
          </Button>
        </div>
      </section>
      <section className="flex flex-wrap items-center gap-4 rounded-lg border border-border bg-card p-4">
        <div className="min-w-0 flex-1">
          <h3 className="text-[13px] font-semibold">{archived ? "Restore module" : "Archive module"}</h3>
          <p className="text-[13px] text-muted-foreground">
            {archived
              ? "Brings the module and its records back for everyone with access."
              : "Hides the module and its records. Nothing is deleted; you can restore it."}
          </p>
        </div>
        <Button
          variant={archived ? "outline" : "destructive-outline"}
          disabled={setStatus.isPending}
          onClick={async () => {
            await setStatus.mutateAsync({ version: module.version, archive: !archived }).catch(() => undefined);
          }}
        >
          {archived ? <ArchiveRestore /> : <Archive />} {archived ? "Restore" : "Archive"}
        </Button>
      </section>
    </div>
  );
}

/* ---------------------------------------------------------------- history tab */

function HistoryTab({ module }: { module: ModuleDefinition }) {
  const { orgId } = useOrg();
  const [page, setPage] = React.useState(0);
  const [open, setOpen] = React.useState<number | null>(null);
  const versions = useModuleVersions(orgId, module.apiKey, page);
  const members = useMembers(orgId);
  const actor = (id?: string | null) => {
    if (!id) return "System";
    const member = members.data?.get(id);
    return member?.displayName ?? member?.email ?? `User ${shortId(id)}`;
  };
  if (versions.isLoading) return <Skeleton className="h-48 w-full rounded-lg" />;
  if (versions.isError) return <ErrorState error={versions.error} onRetry={() => void versions.refetch()} />;
  const items = versions.data?.items ?? [];
  if (items.length === 0) return <EmptyState size="sm" icon={History} title="No versions yet" />;
  return (
    <div className="flex flex-col gap-3">
      <ol className="divide-y divide-border overflow-hidden rounded-lg border border-border bg-card">
        {items.map((version) => {
          const snapshotFields = version.snapshot.fields;
          const fieldCount = Array.isArray(snapshotFields) ? snapshotFields.length : null;
          const expanded = open === version.version;
          return (
            <li key={version.version}>
              <button
                type="button"
                aria-expanded={expanded}
                onClick={() => setOpen(expanded ? null : version.version)}
                className="focus-ring flex w-full items-center gap-3 px-4 py-2.5 text-left text-[13px] hover:bg-surface"
              >
                <Badge variant={version.version === module.version ? "default" : "outline"} className="tabular">
                  v{version.version}
                </Badge>
                <span className="min-w-0 flex-1 truncate">
                  {actor(version.createdBy)}
                  {fieldCount !== null ? <span className="text-muted-foreground"> · {fieldCount} fields</span> : null}
                </span>
                <SimpleTooltip label={formatDateTime(version.createdAt)}>
                  <span className="text-xs text-muted-foreground">{formatRelative(version.createdAt)}</span>
                </SimpleTooltip>
                <ChevronRight className={cn("size-4 text-subtle-foreground transition-transform", expanded && "rotate-90")} />
              </button>
              {expanded ? (
                <pre className="max-h-96 overflow-auto border-t border-border bg-surface p-3 font-mono text-xs leading-relaxed">
                  {JSON.stringify(version.snapshot, null, 2)}
                </pre>
              ) : null}
            </li>
          );
        })}
      </ol>
      {versions.data && versions.data.totalPages > 1 ? (
        <div className="flex justify-end">
          <Pagination page={versions.data.page} totalPages={versions.data.totalPages} onPageChange={setPage} />
        </div>
      ) : null}
    </div>
  );
}

/* ---------------------------------------------------------------- editor */

export function ModuleEditorPage({ moduleKey }: { moduleKey: string }) {
  const { orgId } = useOrg();
  const modules = useAllModules(orgId);
  const definition = modules.data?.find((m) => m.apiKey === moduleKey);
  const [tab, setTab] = React.useState("fields");
  return (
    <AdminGate>
      {modules.isLoading ? (
        <div className="flex flex-col gap-4 px-4 pt-6 sm:px-6 lg:px-8">
          <Skeleton className="h-6 w-48" />
          <Skeleton className="h-96 w-full rounded-lg" />
        </div>
      ) : modules.isError ? (
        <ErrorState error={modules.error} onRetry={() => void modules.refetch()} />
      ) : !definition ? (
        <EmptyState
          icon={Boxes}
          title="Module not found"
          action={
            <Button variant="outline" asChild>
              <Link href={adminPath(orgId, "modules")}>All modules</Link>
            </Button>
          }
        />
      ) : (
        <>
          <PageHeader
            icon={
              <span className="mt-0.5 grid size-9 shrink-0 place-items-center rounded-lg border border-border bg-surface text-muted-foreground">
                <ModuleGlyph moduleKey={definition.apiKey} className="size-[18px]" />
              </span>
            }
            title={definition.labelPlural}
            description={
              <>
                <code className="font-mono text-xs">{definition.apiKey}</code> · version {definition.version}
                {definition.status === "ARCHIVED" ? " · archived" : null}
              </>
            }
            actions={
              definition.status !== "ARCHIVED" ? (
                <Button variant="outline" asChild>
                  <Link href={modulePath(orgId, definition.apiKey)}>Open records</Link>
                </Button>
              ) : null
            }
          />
          <PageBody>
            <Tabs value={tab} onValueChange={setTab}>
              <TabsList className="mb-5">
                <TabsTrigger value="fields">
                  <Rows3 /> Fields
                </TabsTrigger>
                <TabsTrigger value="layout">
                  <LayoutTemplate /> Layout
                </TabsTrigger>
                <TabsTrigger value="settings">
                  <Settings2 /> Settings
                </TabsTrigger>
                <TabsTrigger value="history">
                  <History /> History
                </TabsTrigger>
              </TabsList>
              <TabsContent value="fields">
                <FieldsTab module={definition} />
              </TabsContent>
              <TabsContent value="layout">
                <LayoutBuilder key={definition.version} module={definition} />
              </TabsContent>
              <TabsContent value="settings">
                <SettingsTab key={definition.version} module={definition} />
              </TabsContent>
              <TabsContent value="history">
                <HistoryTab module={definition} />
              </TabsContent>
            </Tabs>
          </PageBody>
        </>
      )}
    </AdminGate>
  );
}
