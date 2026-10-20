"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
  type RowSelectionState,
  type VisibilityState,
} from "@tanstack/react-table";
import {
  ArrowDown,
  ArrowUp,
  ChevronsUpDown,
  Columns3,
  Eye,
  MoreHorizontal,
  Pencil,
  Rows3,
  Rows4,
  Search,
  SearchX,
  Trash2,
  Undo2,
  X,
} from "lucide-react";

import { FilterBuilder, FilterChips } from "@/components/records/filter-builder";
import { ConfirmDeleteDialog, useRecordActions } from "@/components/records/record-actions";
import { EmptyValue, FieldValue } from "@/components/records/field-value";
import { useTableUrlState, PAGE_SIZES } from "@/components/records/use-table-url-state";
import { ErrorState } from "@/components/common/error-state";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Pagination } from "@/components/ui/pagination";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { SimpleTooltip } from "@/components/ui/tooltip";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useRecords } from "@/lib/api";
import { parseSort, serializeSort } from "@/lib/api/record-query";
import { displayField, filterableFields, isNumericType, recordTitle, visibleFields } from "@/lib/metadata/fields";
import { formatDateTime, formatInteger, formatRelative } from "@/lib/metadata/format";
import { moduleIcon, recordPath } from "@/lib/navigation";
import { cn } from "@/lib/utils";
import { useUiStore } from "@/store/ui-store";
import type { FieldDefinition, ModuleDefinition } from "@/types/metadata";
import type { ModuleCapabilities } from "@/types/org";
import type { RecordItem } from "@/types/records";

const SELECT_COLUMN = "__select";
const ACTIONS_COLUMN = "__actions";
const SYSTEM_COLUMNS = [
  { id: "updated_at", label: "Updated" },
  { id: "created_at", label: "Created" },
] as const;

interface ColumnMeta {
  label: string;
  sortKey?: string;
  numeric?: boolean;
  hideable?: boolean;
}

function defaultHidden(fields: FieldDefinition[]) {
  const hidden: string[] = ["created_at"];
  let shown = 0;
  for (const field of fields) {
    const wide = field.type === "LONG_TEXT" || field.type === "JSON" || field.sensitive;
    if (wide || shown >= 8) hidden.push(field.apiKey);
    else shown += 1;
  }
  return hidden;
}

export interface RecordTableProps {
  orgId: string;
  module: ModuleDefinition;
  capabilities: ModuleCapabilities;
  /** Rendered in a bar above the table when rows are selected. */
  bulkActions?: (selected: RecordItem[], clear: () => void) => React.ReactNode;
  /** Extra toolbar controls (e.g. export). */
  toolbarActions?: React.ReactNode;
  /** Call to action shown in the empty state. */
  emptyAction?: React.ReactNode;
}

export function RecordTable({ orgId, module, capabilities, bulkActions, toolbarActions, emptyAction }: RecordTableProps) {
  const router = useRouter();
  const { state, update, query } = useTableUrlState();
  const records = useRecords(orgId, module.apiKey, query);
  const density = useUiStore((s) => s.density);
  const setDensity = useUiStore((s) => s.setDensity);
  const storedHidden = useUiStore((s) => s.hiddenColumns[module.apiKey]);
  const setHiddenColumns = useUiStore((s) => s.setHiddenColumns);
  const { deleteRecords, restoreRecord, deleting } = useRecordActions(orgId, module);

  const fields = React.useMemo(() => visibleFields(module, capabilities), [module, capabilities]);
  const filterFields = React.useMemo(() => filterableFields(module, capabilities), [module, capabilities]);
  const titleField = React.useMemo(() => displayField(module), [module]);
  const searchable = React.useMemo(() => fields.filter((f) => f.searchable), [fields]);
  const hidden = storedHidden ?? defaultHidden(fields);

  const [search, setSearch] = React.useState(state.q);
  const searchTimer = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const onSearch = (value: string) => {
    setSearch(value);
    if (searchTimer.current) clearTimeout(searchTimer.current);
    searchTimer.current = setTimeout(() => update({ q: value }), 300);
  };
  React.useEffect(() => () => {
    if (searchTimer.current) clearTimeout(searchTimer.current);
  }, []);

  const selectionScope = JSON.stringify(query);
  const [selection, setSelection] = React.useState<{ scope: string; rows: RowSelectionState }>({ scope: "", rows: {} });
  const rowSelection = selection.scope === selectionScope ? selection.rows : {};
  const [pendingDelete, setPendingDelete] = React.useState<RecordItem[] | null>(null);

  const sort = parseSort(state.sort)[0];
  const toggleSort = (key: string) => {
    if (!sort || sort.field !== key) update({ sort: serializeSort([{ field: key, desc: false }]) });
    else if (!sort.desc) update({ sort: serializeSort([{ field: key, desc: true }]) });
    else update({ sort: "" });
  };

  const columns = React.useMemo<ColumnDef<RecordItem>[]>(() => {
    const cols: ColumnDef<RecordItem>[] = [
      {
        id: SELECT_COLUMN,
        enableHiding: false,
        header: ({ table }) => (
          <Checkbox
            aria-label="Select all rows on this page"
            checked={table.getIsAllPageRowsSelected() ? true : table.getIsSomePageRowsSelected() ? "indeterminate" : false}
            onCheckedChange={(checked) => table.toggleAllPageRowsSelected(checked === true)}
          />
        ),
        cell: ({ row }) => (
          <Checkbox
            aria-label="Select row"
            checked={row.getIsSelected()}
            onCheckedChange={(checked) => row.toggleSelected(checked === true)}
          />
        ),
      },
    ];
    for (const field of fields) {
      const isTitle = field.apiKey === titleField?.apiKey;
      cols.push({
        id: field.apiKey,
        meta: {
          label: field.label,
          sortKey: field.sortable ? field.apiKey : undefined,
          numeric: isNumericType(field.type),
          hideable: !isTitle,
        } satisfies ColumnMeta,
        enableHiding: !isTitle,
        header: field.label,
        cell: ({ row }) => {
          const value = row.original.data[field.apiKey];
          if (isTitle) {
            return (
              <Link
                href={recordPath(orgId, module.apiKey, row.original.id)}
                className="focus-ring block max-w-72 truncate rounded-sm font-medium text-foreground hover:underline"
              >
                {recordTitle(module, row.original)}
              </Link>
            );
          }
          return <FieldValue orgId={orgId} field={field} value={value} />;
        },
      });
    }
    for (const system of SYSTEM_COLUMNS) {
      cols.push({
        id: system.id,
        meta: { label: system.label, sortKey: system.id, hideable: true } satisfies ColumnMeta,
        header: system.label,
        cell: ({ row }) => {
          const value = system.id === "updated_at" ? row.original.updatedAt : row.original.createdAt;
          return value ? (
            <SimpleTooltip label={formatDateTime(value)}>
              <span className="whitespace-nowrap text-muted-foreground">{formatRelative(value)}</span>
            </SimpleTooltip>
          ) : (
            <EmptyValue />
          );
        },
      });
    }
    cols.push({ id: ACTIONS_COLUMN, enableHiding: false, header: () => <span className="sr-only">Actions</span> });
    return cols;
  }, [fields, titleField, orgId, module]);

  const columnVisibility = React.useMemo<VisibilityState>(
    () => Object.fromEntries(hidden.map((id) => [id, false])),
    [hidden]
  );

  const page = records.data;
  // eslint-disable-next-line react-hooks/incompatible-library -- TanStack Table v8 is not React Compiler compatible; the compiler is not enabled.
  const table = useReactTable({
    data: page?.items ?? [],
    columns,
    getRowId: (row) => row.id,
    getCoreRowModel: getCoreRowModel(),
    manualPagination: true,
    manualSorting: true,
    manualFiltering: true,
    pageCount: page?.totalPages ?? -1,
    enableRowSelection: true,
    state: { columnVisibility, rowSelection },
    onRowSelectionChange: (updater) => {
      const next = typeof updater === "function" ? updater(rowSelection) : updater;
      setSelection({ scope: selectionScope, rows: next });
    },
    onColumnVisibilityChange: (updater) => {
      const next = typeof updater === "function" ? updater(columnVisibility) : updater;
      setHiddenColumns(
        module.apiKey,
        Object.entries(next)
          .filter(([, visible]) => !visible)
          .map(([id]) => id)
      );
    },
  });

  const selectedRows = table.getSelectedRowModel().rows.map((row) => row.original);
  const clearSelection = () => setSelection({ scope: selectionScope, rows: {} });
  const hasQuery = Boolean(state.q || state.filters.length);
  const compact = density === "compact";
  const rowHeight = compact ? "h-8" : "h-11";

  const rowActions = (record: RecordItem) => (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon-xs"
          aria-label="Row actions"
          className="opacity-0 group-hover/row:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100"
        >
          <MoreHorizontal />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem asChild>
          <Link href={recordPath(orgId, module.apiKey, record.id)}>
            <Eye /> Open
          </Link>
        </DropdownMenuItem>
        {record.deletedAt ? (
          capabilities.update || capabilities.delete ? (
            <DropdownMenuItem onSelect={() => void restoreRecord(record)}>
              <Undo2 /> Restore
            </DropdownMenuItem>
          ) : null
        ) : (
          <>
            {capabilities.update ? (
              <DropdownMenuItem asChild>
                <Link href={recordPath(orgId, module.apiKey, record.id, "/edit")}>
                  <Pencil /> Edit
                </Link>
              </DropdownMenuItem>
            ) : null}
            {capabilities.delete ? (
              <>
                <DropdownMenuSeparator />
                <DropdownMenuItem variant="destructive" onSelect={() => setPendingDelete([record])}>
                  <Trash2 /> Delete
                </DropdownMenuItem>
              </>
            ) : null}
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );

  const onRowClick = (event: React.MouseEvent, record: RecordItem) => {
    const target = event.target as HTMLElement;
    if (target.closest("a,button,input,[role=checkbox],[role=menuitem],[data-no-row-click]")) return;
    if (window.getSelection()?.toString()) return;
    const href = recordPath(orgId, module.apiKey, record.id);
    if (event.metaKey || event.ctrlKey) window.open(href, "_blank");
    else router.push(href);
  };

  const hideableColumns = table.getAllLeafColumns().filter((column) => column.getCanHide());

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        {searchable.length > 0 ? (
          <div className="relative w-full sm:w-64">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
            <Input
              type="search"
              value={search}
              onChange={(event) => onSearch(event.target.value)}
              placeholder={`Search ${module.labelPlural.toLowerCase()}…`}
              aria-label={`Search ${module.labelPlural}`}
              className="h-8 pl-8 pr-7 text-[13px] [&::-webkit-search-cancel-button]:hidden"
            />
            {search ? (
              <button
                type="button"
                aria-label="Clear search"
                onClick={() => {
                  setSearch("");
                  update({ q: "" });
                }}
                className="focus-ring absolute right-1.5 top-1/2 -translate-y-1/2 rounded-sm p-0.5 text-muted-foreground hover:text-foreground"
              >
                <X className="size-3.5" />
              </button>
            ) : null}
          </div>
        ) : null}
        {filterFields.length > 0 ? (
          <FilterBuilder orgId={orgId} fields={filterFields} filters={state.filters} onApply={(filters) => update({ filters })} />
        ) : null}
        <FilterChips orgId={orgId} fields={filterFields} filters={state.filters} onChange={(filters) => update({ filters })} />

        <div className="ml-auto flex items-center gap-1.5">
          {state.deleted ? (
            <Button variant="outline" size="sm" onClick={() => update({ deleted: false })}>
              <X /> Leave trash
            </Button>
          ) : null}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" size="sm" aria-label="View options">
                <Columns3 /> <span className="hidden sm:inline">View</span>
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="max-h-[min(28rem,var(--radix-dropdown-menu-content-available-height))] w-56 overflow-y-auto">
              <DropdownMenuLabel>Density</DropdownMenuLabel>
              <DropdownMenuCheckboxItem checked={!compact} onCheckedChange={() => setDensity("comfortable")}>
                <Rows3 /> Comfortable
              </DropdownMenuCheckboxItem>
              <DropdownMenuCheckboxItem checked={compact} onCheckedChange={() => setDensity("compact")}>
                <Rows4 /> Compact
              </DropdownMenuCheckboxItem>
              <DropdownMenuSeparator />
              <DropdownMenuCheckboxItem checked={state.deleted} onCheckedChange={(checked) => update({ deleted: checked })}>
                Show deleted records
              </DropdownMenuCheckboxItem>
              <DropdownMenuSeparator />
              <DropdownMenuLabel>Columns</DropdownMenuLabel>
              {hideableColumns.map((column) => (
                <DropdownMenuCheckboxItem
                  key={column.id}
                  checked={column.getIsVisible()}
                  onCheckedChange={(checked) => column.toggleVisibility(checked)}
                  onSelect={(event) => event.preventDefault()}
                >
                  {(column.columnDef.meta as ColumnMeta | undefined)?.label ?? column.id}
                </DropdownMenuCheckboxItem>
              ))}
              {storedHidden ? (
                <>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onSelect={() => setHiddenColumns(module.apiKey, defaultHidden(fields))}>
                    Reset columns
                  </DropdownMenuItem>
                </>
              ) : null}
            </DropdownMenuContent>
          </DropdownMenu>
          {toolbarActions}
        </div>
      </div>

      {selectedRows.length > 0 ? (
        <div
          role="region"
          aria-label="Bulk actions"
          className="flex h-10 items-center gap-2 rounded-lg border border-primary/25 bg-primary-soft/60 px-3 text-[13px] animate-pop-in"
        >
          <span className="font-medium text-primary-soft-foreground tabular">{selectedRows.length} selected</span>
          <Button variant="subtle" size="xs" onClick={clearSelection}>
            Clear
          </Button>
          <div className="ml-auto flex items-center gap-1.5">
            {bulkActions?.(selectedRows, clearSelection)}
            {capabilities.delete && !state.deleted ? (
              <Button variant="destructive-outline" size="xs" onClick={() => setPendingDelete(selectedRows)}>
                <Trash2 /> Delete
              </Button>
            ) : null}
          </div>
        </div>
      ) : null}

      <div className="relative overflow-hidden rounded-lg border border-border bg-card">
        {records.isFetching && records.data ? (
          <div className="absolute inset-x-0 top-0 z-10 h-0.5 overflow-hidden bg-primary/10" aria-hidden>
            <div className="h-full w-1/3 animate-[shimmer_1s_ease-in-out_infinite] bg-primary/60" />
          </div>
        ) : null}
        {records.isError && !records.data ? (
          <ErrorState error={records.error} onRetry={() => void records.refetch()} />
        ) : (
          <Table aria-busy={records.isFetching || undefined} aria-label={module.labelPlural}>
            <TableHeader>
              {table.getHeaderGroups().map((group) => (
                <tr key={group.id}>
                  {group.headers.map((header) => {
                    const meta = header.column.columnDef.meta as ColumnMeta | undefined;
                    const sortKey = meta?.sortKey;
                    const active = sort?.field === sortKey && sortKey;
                    return (
                      <TableHead
                        key={header.id}
                        aria-sort={active ? (sort.desc ? "descending" : "ascending") : undefined}
                        className={cn(
                          header.column.id === SELECT_COLUMN && "w-10 pr-0",
                          header.column.id === ACTIONS_COLUMN && "w-10",
                          meta?.numeric && "text-right"
                        )}
                      >
                        {sortKey ? (
                          <button
                            type="button"
                            onClick={() => toggleSort(sortKey)}
                            className={cn(
                              "focus-ring -mx-1 inline-flex items-center gap-1 rounded-sm px-1 py-0.5 transition-colors hover:text-foreground",
                              active && "text-foreground",
                              meta?.numeric && "flex-row-reverse"
                            )}
                          >
                            {flexRender(header.column.columnDef.header, header.getContext())}
                            {active ? (
                              sort.desc ? <ArrowDown className="size-3" /> : <ArrowUp className="size-3" />
                            ) : (
                              <ChevronsUpDown className="size-3 opacity-40" />
                            )}
                          </button>
                        ) : (
                          flexRender(header.column.columnDef.header, header.getContext())
                        )}
                      </TableHead>
                    );
                  })}
                </tr>
              ))}
            </TableHeader>
            <TableBody className={cn(records.isFetching && records.data && "opacity-70 transition-opacity")}>
              {records.isLoading ? (
                Array.from({ length: Math.min(state.size, 8) }, (_, i) => (
                  <TableRow key={`skeleton-${i}`} className="hover:bg-transparent">
                    {table.getVisibleLeafColumns().map((column, index) => (
                      <TableCell key={column.id} className={rowHeight}>
                        {column.id === SELECT_COLUMN ? (
                          <Skeleton className="size-4 rounded-[4px]" />
                        ) : column.id === ACTIONS_COLUMN ? null : (
                          <Skeleton className={cn("h-3.5", index === 1 ? "w-32" : i % 2 ? "w-16" : "w-24")} />
                        )}
                      </TableCell>
                    ))}
                  </TableRow>
                ))
              ) : table.getRowModel().rows.length === 0 ? (
                <tr>
                  <td colSpan={table.getVisibleLeafColumns().length}>
                    {hasQuery ? (
                      <EmptyState
                        icon={SearchX}
                        title={`No ${module.labelPlural.toLowerCase()} match`}
                        description="Try a different search or remove some filters."
                        action={
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => {
                              setSearch("");
                              update({ q: "", filters: [] });
                            }}
                          >
                            Clear search and filters
                          </Button>
                        }
                      />
                    ) : state.deleted ? (
                      <EmptyState icon={Trash2} title="Trash is empty" description="Deleted records show up here." />
                    ) : (
                      <EmptyState
                        icon={moduleIcon(module.apiKey)}
                        title={`No ${module.labelPlural.toLowerCase()} yet`}
                        description={
                          capabilities.create
                            ? `Add your first ${module.labelSingular.toLowerCase()}, or import a spreadsheet.`
                            : `Nothing has been shared with you here yet.`
                        }
                        action={capabilities.create ? emptyAction : undefined}
                      />
                    )}
                  </td>
                </tr>
              ) : (
                table.getRowModel().rows.map((row) => (
                  <TableRow
                    key={row.id}
                    data-state={row.getIsSelected() ? "selected" : undefined}
                    onClick={(event) => onRowClick(event, row.original)}
                    className={cn("cursor-pointer", row.original.deletedAt && "text-muted-foreground")}
                  >
                    {row.getVisibleCells().map((cell) => {
                      const meta = cell.column.columnDef.meta as ColumnMeta | undefined;
                      return (
                        <TableCell
                          key={cell.id}
                          className={cn(
                            rowHeight,
                            "max-w-80",
                            cell.column.id === SELECT_COLUMN && "w-10 pr-0",
                            meta?.numeric && "text-right"
                          )}
                        >
                          {cell.column.id === ACTIONS_COLUMN
                            ? rowActions(row.original)
                            : flexRender(cell.column.columnDef.cell, cell.getContext())}
                        </TableCell>
                      );
                    })}
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        )}
      </div>

      {page && page.totalElements > 0 ? (
        <div className="flex flex-wrap items-center justify-between gap-3 text-[13px] text-muted-foreground">
          <div className="flex items-center gap-3">
            <span className="tabular">
              {formatInteger(page.page * page.size + 1)}–{formatInteger(page.page * page.size + page.items.length)} of{" "}
              {formatInteger(page.totalElements)}
            </span>
            <Select value={String(state.size)} onValueChange={(size) => update({ size: Number(size) })}>
              <SelectTrigger size="sm" className="h-7 w-[7.5rem] text-xs" aria-label="Rows per page">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {PAGE_SIZES.map((size) => (
                  <SelectItem key={size} value={String(size)}>
                    {size} per page
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <Pagination page={page.page} totalPages={page.totalPages} onPageChange={(next) => update({ page: next })} />
        </div>
      ) : null}

      {pendingDelete ? (
        <ConfirmDeleteDialog
          open
          onOpenChange={(open) => !open && setPendingDelete(null)}
          module={module}
          count={pendingDelete.length}
          title={pendingDelete.length === 1 ? recordTitle(module, pendingDelete[0]) : undefined}
          pending={deleting}
          onConfirm={async () => {
            await deleteRecords(pendingDelete);
            clearSelection();
          }}
        />
      ) : null}
    </div>
  );
}
