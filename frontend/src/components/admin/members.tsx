"use client";

import * as React from "react";
import { toast } from "sonner";
import { Check, Copy, MailPlus, MoreHorizontal, Pencil, Plus, Trash2, UserMinus, Users, X } from "lucide-react";

import { AdminGate, FieldMessage } from "@/components/admin/admin-gate";
import { useOrg } from "@/components/app/org-context";
import { ErrorState } from "@/components/common/error-state";
import { PageBody, PageHeader } from "@/components/layout/page-header";
import { LookupValue } from "@/components/records/field-value";
import { LookupInput } from "@/components/records/inputs/lookup-input";
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
import { Avatar, AvatarFallback, initials } from "@/components/ui/avatar";
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { SimpleTooltip } from "@/components/ui/tooltip";
import {
  ApiError,
  errorMessage,
  useCreateInvitation,
  useInvitations,
  useMe,
  useMemberPage,
  useRemoveMember,
  useRevokeInvitation,
  useRoles,
  useUpdateMember,
  type InvitationRequest,
} from "@/lib/api";
import { formatDate, formatDateTime, formatRelative } from "@/lib/metadata/format";
import { cn } from "@/lib/utils";
import type { OrgRole } from "@/types/access";
import type { ModuleDefinition } from "@/types/metadata";
import {
  INVITATION_STATUSES,
  type CreatedInvitation,
  type Invitation,
  type Member,
  type MemberAttributes,
  type MembershipRole,
} from "@/types/org";

const MEMBERSHIP_ROLES: { value: MembershipRole; label: string; hint: string }[] = [
  { value: "MEMBER", label: "Member", hint: "Access decided by the org role" },
  { value: "ADMIN", label: "Admin", hint: "Full access, manages members and settings" },
  { value: "OWNER", label: "Owner", hint: "Admin who can also manage admins" },
];
const NO_ROLE = "__none";

const STATUS_VARIANT: Record<string, "default" | "success" | "neutral" | "destructive" | "outline"> = {
  PENDING: "default",
  ACCEPTED: "success",
  EXPIRED: "neutral",
  REVOKED: "destructive",
};

/* ---------------------------------------------------------------- attributes */

function AttributeChips({ attributes, modules }: { attributes: MemberAttributes | null | undefined; modules: ModuleDefinition[] }) {
  const { orgId } = useOrg();
  const entries = Object.entries(attributes ?? {});
  if (entries.length === 0) return <span className="text-subtle-foreground">–</span>;
  return (
    <span className="flex flex-wrap gap-1">
      {entries.flatMap(([key, raw]) => {
        const values = Array.isArray(raw) ? raw : [raw];
        const target = modules.find((m) => m.apiKey === key);
        return values.map((value) => (
          <span
            key={`${key}-${value}`}
            className="inline-flex h-6 max-w-56 items-center gap-1 rounded-md border border-border bg-surface px-1.5 text-xs"
          >
            <span className="text-muted-foreground">{target?.labelSingular ?? key}</span>
            <span className="truncate font-medium">
              {target ? <LookupValue orgId={orgId} targetModuleKey={key} id={value} link={false} /> : value}
            </span>
          </span>
        ));
      })}
    </span>
  );
}

interface AttributeRow {
  key: string;
  values: string[];
}

function toRows(attributes: MemberAttributes | null | undefined): AttributeRow[] {
  return Object.entries(attributes ?? {}).map(([key, raw]) => ({ key, values: Array.isArray(raw) ? [...raw] : [raw] }));
}

function fromRows(rows: AttributeRow[]): MemberAttributes {
  const result: MemberAttributes = {};
  for (const row of rows) {
    const values = row.values.map((v) => v.trim()).filter(Boolean);
    if (!row.key.trim() || values.length === 0) continue;
    result[row.key.trim()] = values.length === 1 ? values[0] : values;
  }
  return result;
}

/** Key/value editor; keys naming a module (department, company, student…) get record pickers. */
function AttributesEditor({ rows, onChange, modules }: { rows: AttributeRow[]; onChange: (rows: AttributeRow[]) => void; modules: ModuleDefinition[] }) {
  const { orgId } = useOrg();
  const listId = React.useId();
  const preferred = ["department", "company", "student", "batch"].filter((key) => modules.some((m) => m.apiKey === key));
  const patch = (index: number, row: Partial<AttributeRow>) => onChange(rows.map((r, i) => (i === index ? { ...r, ...row } : r)));
  return (
    <div className="grid gap-2">
      {rows.length === 0 ? <p className="text-[13px] text-muted-foreground">No attributes.</p> : null}
      {rows.map((row, index) => {
        const target = modules.find((m) => m.apiKey === row.key);
        return (
          <div key={index} className="grid grid-cols-[8.5rem_minmax(0,1fr)_auto] items-start gap-2">
            <Input
              aria-label="Attribute name"
              list={listId}
              value={row.key}
              placeholder="department"
              className="h-9 font-mono text-xs"
              onChange={(event) => patch(index, { key: event.target.value.trim(), values: [] })}
            />
            <div className="grid gap-1.5">
              {target ? (
                <>
                  {[...row.values, ""].map((value, valueIndex) => (
                    <LookupInput
                      key={`${valueIndex}-${value}`}
                      orgId={orgId}
                      targetModuleKey={target.apiKey}
                      value={value || null}
                      placeholder={valueIndex === 0 ? `Pick a ${target.labelSingular.toLowerCase()}…` : "Add another…"}
                      onChange={(next) => {
                        const values = [...row.values];
                        if (next) values[valueIndex] = next;
                        else values.splice(valueIndex, 1);
                        patch(index, { values: [...new Set(values.filter(Boolean))] });
                      }}
                    />
                  ))}
                </>
              ) : (
                <Input
                  aria-label={`Values for ${row.key || "attribute"}`}
                  value={row.values.join(", ")}
                  placeholder="value, or several separated by commas"
                  onChange={(event) => patch(index, { values: event.target.value.split(",").map((v) => v.trim()) })}
                />
              )}
            </div>
            <Button variant="ghost" size="icon-sm" aria-label="Remove attribute" onClick={() => onChange(rows.filter((_, i) => i !== index))}>
              <X />
            </Button>
          </div>
        );
      })}
      <datalist id={listId}>
        {modules.map((m) => (
          <option key={m.apiKey} value={m.apiKey}>
            {m.labelSingular}
          </option>
        ))}
      </datalist>
      <div className="flex flex-wrap gap-1.5">
        {preferred
          .filter((key) => !rows.some((r) => r.key === key))
          .map((key) => (
            <Button key={key} variant="outline" size="xs" onClick={() => onChange([...rows, { key, values: [] }])}>
              <Plus /> {modules.find((m) => m.apiKey === key)?.labelSingular ?? key}
            </Button>
          ))}
        <Button variant="ghost" size="xs" onClick={() => onChange([...rows, { key: "", values: [] }])}>
          <Plus /> Custom attribute
        </Button>
      </div>
    </div>
  );
}

/* ---------------------------------------------------------------- members */

function RoleSelect({
  roles,
  value,
  onChange,
  disabled,
  id,
  label,
}: {
  roles: OrgRole[];
  value: string | null | undefined;
  onChange: (value: string | null) => void;
  disabled?: boolean;
  id?: string;
  label?: string;
}) {
  return (
    <Select value={value ?? NO_ROLE} onValueChange={(next) => onChange(next === NO_ROLE ? null : next)} disabled={disabled}>
      <SelectTrigger id={id} size="sm" aria-label={label} className="h-8 min-w-36 text-[13px]">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={NO_ROLE}>No org role</SelectItem>
        {roles.map((role) => (
          <SelectItem key={role.apiKey} value={role.apiKey}>
            {role.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

function MembershipSelect({
  value,
  onChange,
  disabled,
  id,
  label,
}: {
  value: MembershipRole;
  onChange: (value: MembershipRole) => void;
  disabled?: boolean;
  id?: string;
  label?: string;
}) {
  return (
    <Select value={value} onValueChange={(next) => onChange(next as MembershipRole)} disabled={disabled}>
      <SelectTrigger id={id} size="sm" aria-label={label} className="h-8 min-w-28 text-[13px]">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {MEMBERSHIP_ROLES.map((role) => (
          <SelectItem key={role.value} value={role.value} title={role.hint}>
            {role.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

function EditAttributesDialog({ member, onClose, modules }: { member: Member; onClose: () => void; modules: ModuleDefinition[] }) {
  const { orgId } = useOrg();
  const update = useUpdateMember(orgId);
  const [rows, setRows] = React.useState<AttributeRow[]>(() => toRows(member.attributes));
  const [error, setError] = React.useState<string | null>(null);
  const save = async () => {
    setError(null);
    try {
      await update.mutateAsync({ userId: member.userId, update: { attributes: fromRows(rows) } });
      toast.success("Attributes saved");
      onClose();
    } catch (cause) {
      setError(cause instanceof ApiError && cause.isValidation ? cause.errors.map((e) => e.message).join(" ") : errorMessage(cause));
    }
  };
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>Attributes of {member.displayName ?? member.email}</DialogTitle>
          <DialogDescription>
            Roles use attributes to scope records, e.g. a coordinator sees students of their department.
          </DialogDescription>
        </DialogHeader>
        <AttributesEditor rows={rows} onChange={setRows} modules={modules} />
        <FieldMessage error={error} />
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => void save()} disabled={update.isPending}>
            {update.isPending ? <Spinner className="text-primary-foreground" /> : null}
            Save attributes
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function MembersTab() {
  const { orgId, modules, organization } = useOrg();
  const [page, setPage] = React.useState(0);
  const members = useMemberPage(orgId, page);
  const roles = useRoles(orgId);
  const me = useMe();
  const update = useUpdateMember(orgId);
  const remove = useRemoveMember(orgId);
  const [editing, setEditing] = React.useState<Member | null>(null);
  const [removing, setRemoving] = React.useState<Member | null>(null);
  const canManageAdmins = organization?.role === "OWNER";

  const change = async (member: Member, patch: Parameters<typeof update.mutateAsync>[0]["update"], message: string) => {
    try {
      await update.mutateAsync({ userId: member.userId, update: patch });
      toast.success(message);
    } catch {
      /* toasted by the mutation cache */
    }
  };

  if (members.isLoading) {
    return (
      <div className="flex flex-col gap-2">
        {Array.from({ length: 5 }, (_, i) => (
          <Skeleton key={i} className="h-12 w-full" />
        ))}
      </div>
    );
  }
  if (members.isError) return <ErrorState error={members.error} onRetry={() => void members.refetch()} />;
  const items = members.data?.items ?? [];

  return (
    <div className="flex flex-col gap-3">
      <div className="overflow-x-auto rounded-lg border border-border bg-card">
        <Table aria-label="Members">
          <TableHeader>
            <tr>
              <TableHead>Member</TableHead>
              <TableHead>Access</TableHead>
              <TableHead>Org role</TableHead>
              <TableHead>Attributes</TableHead>
              <TableHead>Joined</TableHead>
              <TableHead className="w-10">
                <span className="sr-only">Actions</span>
              </TableHead>
            </tr>
          </TableHeader>
          <TableBody>
            {items.length === 0 ? (
              <tr>
                <td colSpan={6}>
                  <EmptyState size="sm" icon={Users} title="No members" />
                </td>
              </tr>
            ) : (
              items.map((member) => {
                const self = member.userId === me.data?.id;
                const name = member.displayName ?? member.email ?? member.userId;
                const elevated = member.role === "OWNER" || member.role === "ADMIN";
                const locked = self || (elevated && !canManageAdmins);
                return (
                  <TableRow key={member.userId}>
                    <TableCell className="h-14">
                      <div className="flex min-w-0 items-center gap-2.5">
                        <Avatar className="size-7">
                          <AvatarFallback className="text-[10px]">{initials(name)}</AvatarFallback>
                        </Avatar>
                        <div className="min-w-0">
                          <p className="flex items-center gap-1.5 truncate font-medium">
                            {member.displayName ?? member.email ?? "Unknown user"}
                            {self ? <Badge variant="outline">You</Badge> : null}
                          </p>
                          {member.displayName && member.email ? (
                            <p className="truncate text-xs text-muted-foreground">{member.email}</p>
                          ) : null}
                        </div>
                      </div>
                    </TableCell>
                    <TableCell>
                      <MembershipSelect
                        label={`Access for ${name}`}
                        value={member.role}
                        disabled={locked || update.isPending}
                        onChange={(role) => {
                          if ((role === "OWNER" || role === "ADMIN") && !canManageAdmins) {
                            toast.error("Only owners can make someone an admin or owner");
                            return;
                          }
                          void change(member, { role }, `${name} is now ${role.toLowerCase()}`);
                        }}
                      />
                    </TableCell>
                    <TableCell>
                      <RoleSelect
                        label={`Org role for ${name}`}
                        roles={roles.data ?? []}
                        value={member.roleKey}
                        disabled={locked || update.isPending}
                        onChange={(roleKey) => void change(member, { roleKey }, roleKey ? "Org role changed" : "Org role removed")}
                      />
                    </TableCell>
                    <TableCell className="max-w-80">
                      <AttributeChips attributes={member.attributes} modules={modules} />
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {member.createdAt ? (
                        <SimpleTooltip label={formatDateTime(member.createdAt)}>
                          <span>{formatRelative(member.createdAt)}</span>
                        </SimpleTooltip>
                      ) : (
                        "–"
                      )}
                    </TableCell>
                    <TableCell>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon-xs" aria-label={`Actions for ${name}`}>
                            <MoreHorizontal />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem disabled={locked} onSelect={() => setEditing(member)}>
                            <Pencil /> Edit attributes
                          </DropdownMenuItem>
                          <DropdownMenuSeparator />
                          <DropdownMenuItem variant="destructive" disabled={elevated && !canManageAdmins && !self} onSelect={() => setRemoving(member)}>
                            {self ? <UserMinus /> : <Trash2 />} {self ? "Leave institute" : "Remove"}
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
                );
              })
            )}
          </TableBody>
        </Table>
      </div>
      {members.data && members.data.totalPages > 1 ? (
        <div className="flex justify-end">
          <Pagination page={members.data.page} totalPages={members.data.totalPages} onPageChange={setPage} />
        </div>
      ) : null}

      {editing ? <EditAttributesDialog member={editing} modules={modules} onClose={() => setEditing(null)} /> : null}
      <AlertDialog open={Boolean(removing)} onOpenChange={(open) => !open && setRemoving(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {removing?.userId === me.data?.id ? "Leave this institute?" : `Remove ${removing?.displayName ?? removing?.email}?`}
            </AlertDialogTitle>
            <AlertDialogDescription>
              They lose access immediately. Records they created stay. You can invite them again later.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={async (event) => {
                event.preventDefault();
                if (!removing) return;
                try {
                  await remove.mutateAsync(removing.userId);
                  toast.success("Member removed");
                } finally {
                  setRemoving(null);
                }
              }}
            >
              {remove.isPending ? <Spinner className="text-destructive-foreground" /> : null}
              Remove
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

/* ---------------------------------------------------------------- invitations */

function CopyField({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = React.useState(false);
  const copy = async () => {
    await navigator.clipboard.writeText(value);
    setCopied(true);
    toast.success("Link copied");
    setTimeout(() => setCopied(false), 2000);
  };
  return (
    <div className="flex items-center gap-2">
      <Input readOnly value={value} aria-label={label} className="font-mono text-xs" onFocus={(event) => event.target.select()} />
      <Button variant="outline" onClick={() => void copy()} aria-label="Copy link">
        {copied ? <Check /> : <Copy />} {copied ? "Copied" : "Copy"}
      </Button>
    </div>
  );
}

type Preset = "recruiter" | "coordinator" | "custom";

function InviteDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const { orgId, modules, organization } = useOrg();
  const roles = useRoles(orgId);
  const create = useCreateInvitation(orgId);
  const hasCompany = modules.some((m) => m.apiKey === "company");
  const hasDepartment = modules.some((m) => m.apiKey === "department");
  const [preset, setPreset] = React.useState<Preset>(hasCompany ? "recruiter" : hasDepartment ? "coordinator" : "custom");
  const [email, setEmail] = React.useState("");
  const [recordId, setRecordId] = React.useState<string | null>(null);
  const [membershipRole, setMembershipRole] = React.useState<MembershipRole>("MEMBER");
  const [roleKey, setRoleKey] = React.useState<string | null>(null);
  const [rows, setRows] = React.useState<AttributeRow[]>([]);
  const [errors, setErrors] = React.useState<Record<string, string>>({});
  const [created, setCreated] = React.useState<CreatedInvitation | null>(null);

  const reset = () => {
    setEmail("");
    setRecordId(null);
    setMembershipRole("MEMBER");
    setRoleKey(null);
    setRows([]);
    setErrors({});
    setCreated(null);
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const local: Record<string, string> = {};
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) local.email = "Enter a valid email address";
    if (preset !== "custom" && !recordId) local.record = preset === "recruiter" ? "Pick the company" : "Pick the department";
    setErrors(local);
    if (Object.keys(local).length) return;

    const trimmed = email.trim();
    const generic = (input: { roleKey: string | null; attributes: MemberAttributes; membershipRole: MembershipRole }): InvitationRequest => ({
      kind: "generic",
      input: { email: trimmed, membershipRole: input.membershipRole, roleKey: input.roleKey, attributes: input.attributes },
    });
    let request: InvitationRequest;
    if (preset === "recruiter") request = { kind: "recruiter", companyId: recordId!, email: trimmed };
    else if (preset === "coordinator") request = { kind: "coordinator", departmentId: recordId!, email: trimmed };
    else request = generic({ roleKey, attributes: fromRows(rows), membershipRole });

    try {
      let result: CreatedInvitation;
      try {
        result = await create.mutateAsync(request);
      } catch (error) {
        // CampusHire invite endpoints not deployed: fall back to a generic invitation with the same role and attribute.
        if (request.kind !== "generic" && error instanceof ApiError && error.isNotFound) {
          const key = request.kind === "recruiter" ? "company" : "department";
          const role = roles.data?.find((r) => r.apiKey === request.kind)?.apiKey ?? null;
          result = await create.mutateAsync(generic({ roleKey: role, attributes: { [key]: recordId! }, membershipRole: "MEMBER" }));
        } else {
          throw error;
        }
      }
      setCreated(result);
      toast.success(`Invitation created for ${result.email}`);
    } catch (error) {
      if (error instanceof ApiError && error.isValidation) {
        setErrors(Object.fromEntries(error.errors.map((v) => [v.field.replace(/^attributes\..*/, "attributes"), v.message])));
      } else if (error instanceof ApiError && (error.isConflict || error.isForbidden)) {
        setErrors({ email: error.friendlyMessage });
      } else {
        toast.error("Couldn't create the invitation", { description: errorMessage(error) });
      }
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) reset();
        onOpenChange(next);
      }}
    >
      <DialogContent className="max-w-xl">
        {created ? (
          <>
            <DialogHeader>
              <DialogTitle>Invitation ready</DialogTitle>
              <DialogDescription>
                Send this link to {created.email}. It works once, for that address only, until {formatDate(created.expiresAt)}. It
                isn&apos;t shown again.
              </DialogDescription>
            </DialogHeader>
            <CopyField value={created.acceptUrl} label="Accept link" />
            <DialogFooter>
              <Button variant="outline" onClick={reset}>
                Invite someone else
              </Button>
              <Button onClick={() => onOpenChange(false)}>Done</Button>
            </DialogFooter>
          </>
        ) : (
          <form onSubmit={submit} className="grid gap-4" noValidate>
            <DialogHeader>
              <DialogTitle>Invite to {organization?.name ?? "the institute"}</DialogTitle>
              <DialogDescription>They sign in with this email address to accept.</DialogDescription>
            </DialogHeader>
            <Tabs value={preset} onValueChange={(value) => {
              setPreset(value as Preset);
              setRecordId(null);
              setErrors({});
            }}>
              <TabsList>
                {hasCompany ? <TabsTrigger value="recruiter">Recruiter for company</TabsTrigger> : null}
                {hasDepartment ? <TabsTrigger value="coordinator">Coordinator for department</TabsTrigger> : null}
                <TabsTrigger value="custom">Custom</TabsTrigger>
              </TabsList>
              <div className="grid gap-4 pt-4">
                <div className="grid gap-1.5">
                  <Label htmlFor="invite-email">Email</Label>
                  <Input
                    id="invite-email"
                    type="email"
                    autoFocus
                    value={email}
                    placeholder={preset === "recruiter" ? "hr@company.com" : "name@iiitt.ac.in"}
                    aria-invalid={Boolean(errors.email) || undefined}
                    aria-describedby="invite-email-msg"
                    onChange={(event) => setEmail(event.target.value)}
                  />
                  <FieldMessage id="invite-email-msg" error={errors.email} />
                </div>
                <TabsContent value="recruiter" className="grid gap-1.5">
                  <Label htmlFor="invite-company">Company</Label>
                  <LookupInput id="invite-company" orgId={orgId} targetModuleKey="company" value={recordId} onChange={setRecordId} invalid={Boolean(errors.record)} />
                  <FieldMessage error={errors.record} hint="They get the Recruiter role and see only this company's drives and applicants." />
                </TabsContent>
                <TabsContent value="coordinator" className="grid gap-1.5">
                  <Label htmlFor="invite-department">Department</Label>
                  <LookupInput id="invite-department" orgId={orgId} targetModuleKey="department" value={recordId} onChange={setRecordId} invalid={Boolean(errors.record)} />
                  <FieldMessage error={errors.record} hint="They get the Coordinator role for students and applications of this department." />
                </TabsContent>
                <TabsContent value="custom" className="grid gap-4">
                  <div className="grid gap-4 sm:grid-cols-2">
                    <div className="grid gap-1.5">
                      <Label htmlFor="invite-membership">Access</Label>
                      <MembershipSelect
                        id="invite-membership"
                        value={membershipRole}
                        onChange={setMembershipRole}
                      />
                      <FieldMessage error={errors.membershipRole} />
                    </div>
                    <div className="grid gap-1.5">
                      <Label htmlFor="invite-role">Org role</Label>
                      <RoleSelect id="invite-role" roles={roles.data ?? []} value={roleKey} onChange={setRoleKey} disabled={membershipRole !== "MEMBER"} />
                      <FieldMessage error={errors.roleKey} hint={membershipRole !== "MEMBER" ? "Admins and owners have full access." : undefined} />
                    </div>
                  </div>
                  <div className="grid gap-1.5">
                    <Label>Attributes</Label>
                    <AttributesEditor rows={rows} onChange={setRows} modules={modules} />
                    <FieldMessage error={errors.attributes} />
                  </div>
                </TabsContent>
              </div>
            </Tabs>
            <DialogFooter>
              <Button variant="outline" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={create.isPending}>
                {create.isPending ? <Spinner className="text-primary-foreground" /> : <MailPlus />}
                Create invitation
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}

function InvitationsTab() {
  const { orgId, modules } = useOrg();
  const [status, setStatus] = React.useState<string>("PENDING");
  const [page, setPage] = React.useState(0);
  const invitations = useInvitations(orgId, status, page);
  const roles = useRoles(orgId);
  const revoke = useRevokeInvitation(orgId);
  const [revoking, setRevoking] = React.useState<Invitation | null>(null);
  const roleLabel = (key?: string | null) => (key ? (roles.data?.find((r) => r.apiKey === key)?.label ?? key) : null);
  const items = invitations.data?.items ?? [];

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <Label htmlFor="invite-status" className="text-xs text-muted-foreground">
          Status
        </Label>
        <Select value={status} onValueChange={(next) => {
          setStatus(next);
          setPage(0);
        }}>
          <SelectTrigger id="invite-status" size="sm" className="h-8 w-36 text-[13px]">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">All</SelectItem>
            {INVITATION_STATUSES.map((s) => (
              <SelectItem key={s} value={s}>
                {s.charAt(0) + s.slice(1).toLowerCase()}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      <div className={cn("overflow-x-auto rounded-lg border border-border bg-card", invitations.isFetching && "opacity-80")}>
        {invitations.isError ? (
          <ErrorState error={invitations.error} onRetry={() => void invitations.refetch()} />
        ) : (
          <Table aria-label="Invitations">
            <TableHeader>
              <tr>
                <TableHead>Email</TableHead>
                <TableHead>Role</TableHead>
                <TableHead>Attributes</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Expires</TableHead>
                <TableHead className="w-24">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </tr>
            </TableHeader>
            <TableBody>
              {invitations.isLoading ? (
                Array.from({ length: 3 }, (_, i) => (
                  <TableRow key={i}>
                    <TableCell colSpan={6}>
                      <Skeleton className="h-5 w-full" />
                    </TableCell>
                  </TableRow>
                ))
              ) : items.length === 0 ? (
                <tr>
                  <td colSpan={6}>
                    <EmptyState size="sm" icon={MailPlus} title={status === "PENDING" ? "No pending invitations" : "No invitations"} />
                  </td>
                </tr>
              ) : (
                items.map((invitation) => (
                  <TableRow key={invitation.id}>
                    <TableCell className="h-12 font-medium">{invitation.email}</TableCell>
                    <TableCell>
                      <span className="flex flex-wrap items-center gap-1">
                        <Badge variant="outline">{invitation.membershipRole.charAt(0) + invitation.membershipRole.slice(1).toLowerCase()}</Badge>
                        {invitation.roleKey ? <Badge variant="neutral">{roleLabel(invitation.roleKey)}</Badge> : null}
                      </span>
                    </TableCell>
                    <TableCell className="max-w-72">
                      <AttributeChips attributes={invitation.attributes} modules={modules} />
                    </TableCell>
                    <TableCell>
                      <Badge variant={STATUS_VARIANT[invitation.status] ?? "outline"}>
                        {invitation.status.charAt(0) + invitation.status.slice(1).toLowerCase()}
                      </Badge>
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      <SimpleTooltip label={formatDateTime(invitation.expiresAt)}>
                        <span>{formatRelative(invitation.expiresAt)}</span>
                      </SimpleTooltip>
                    </TableCell>
                    <TableCell className="text-right">
                      {invitation.status === "PENDING" ? (
                        <Button variant="ghost" size="xs" className="text-destructive" onClick={() => setRevoking(invitation)}>
                          Revoke
                        </Button>
                      ) : null}
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        )}
      </div>
      {invitations.data && invitations.data.totalPages > 1 ? (
        <div className="flex justify-end">
          <Pagination page={invitations.data.page} totalPages={invitations.data.totalPages} onPageChange={setPage} />
        </div>
      ) : null}
      <p className="text-xs text-muted-foreground">
        Accept links are shown once, when the invitation is created. To resend, revoke it and create a new one.
      </p>
      <AlertDialog open={Boolean(revoking)} onOpenChange={(open) => !open && setRevoking(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Revoke the invitation for {revoking?.email}?</AlertDialogTitle>
            <AlertDialogDescription>The link stops working immediately.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep it</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={async (event) => {
                event.preventDefault();
                if (!revoking) return;
                try {
                  await revoke.mutateAsync(revoking.id);
                  toast.success("Invitation revoked");
                } finally {
                  setRevoking(null);
                }
              }}
            >
              {revoke.isPending ? <Spinner className="text-destructive-foreground" /> : null}
              Revoke
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

export function MembersPage() {
  const [tab, setTab] = React.useState("members");
  const [inviting, setInviting] = React.useState(false);
  return (
    <AdminGate>
      <PageHeader
        title="Members"
        description="Who can access the institute. Access (member, admin, owner) sets the baseline; the org role and attributes decide what members see."
        actions={
          <Button onClick={() => setInviting(true)}>
            <MailPlus /> Invite
          </Button>
        }
      />
      <PageBody>
        <Tabs value={tab} onValueChange={setTab}>
          <TabsList className="mb-4">
            <TabsTrigger value="members">
              <Users /> Members
            </TabsTrigger>
            <TabsTrigger value="invitations">
              <MailPlus /> Invitations
            </TabsTrigger>
          </TabsList>
          <TabsContent value="members">
            <MembersTab />
          </TabsContent>
          <TabsContent value="invitations">
            <InvitationsTab />
          </TabsContent>
        </Tabs>
      </PageBody>
      <InviteDialog open={inviting} onOpenChange={setInviting} />
    </AdminGate>
  );
}
