"use client";

import * as React from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  pointerWithin,
  rectIntersection,
  useSensor,
  useSensors,
  type Announcements,
  type CollisionDetection,
  type DragEndEvent,
  type DragStartEvent,
  type KeyboardCoordinateGetter,
} from "@dnd-kit/core";
import { toast } from "sonner";
import { Search, X } from "lucide-react";

import { useOrg } from "@/components/app/org-context";
import { BoardColumn } from "@/components/board/board-column";
import { CardBody, type CardDragData } from "@/components/board/board-card";
import { FilterBuilder, FilterChips } from "@/components/records/filter-builder";
import { useTableUrlState } from "@/components/records/use-table-url-state";
import { TransitionDialog } from "@/components/workflow/transition-dialog";
import { Input } from "@/components/ui/input";
import { RecordTitle } from "@/components/records/field-value";
import { queryKeys, useTransitionRecord, type BoardColumnData } from "@/lib/api";
import { activeFields, displayField, filterableFields, recordTitle, visibleFields } from "@/lib/metadata/fields";
import { isWideField } from "@/lib/metadata/layout";
import { allowedTargets, boardStates } from "@/lib/workflow";
import type { FieldDefinition, ModuleDefinition } from "@/types/metadata";
import type { ModuleCapabilities } from "@/types/org";
import type { RecordItem } from "@/types/records";
import type { Workflow, WorkflowState, WorkflowTransitionDefinition } from "@/types/workflow";

const CARD_FIELD_TYPES = new Set<FieldDefinition["type"]>([
  "TEXT",
  "EMAIL",
  "PHONE",
  "INTEGER",
  "DECIMAL",
  "CURRENCY",
  "DATE",
  "DATETIME",
  "SINGLE_SELECT",
  "LOOKUP",
  "BOOLEAN",
]);

/** Arrow keys jump between stage columns instead of moving the card pixel by pixel. */
const columnCoordinates: KeyboardCoordinateGetter = (event, { currentCoordinates, context }) => {
  const { collisionRect, droppableRects, droppableContainers } = context;
  if (!collisionRect) return undefined;
  const forward = event.code === "ArrowRight" || event.code === "ArrowDown";
  const backward = event.code === "ArrowLeft" || event.code === "ArrowUp";
  if (!forward && !backward) return undefined;
  event.preventDefault();
  const center = collisionRect.left + collisionRect.width / 2;
  const centers = droppableContainers
    .getEnabled()
    .map((container) => droppableRects.get(container.id))
    .filter((rect): rect is NonNullable<typeof rect> => Boolean(rect))
    .map((rect) => rect.left + rect.width / 2)
    .filter((x) => (forward ? x > center + 1 : x < center - 1))
    .sort((a, b) => (forward ? a - b : b - a));
  const next = centers[0];
  if (next === undefined) return currentCoordinates;
  return { x: currentCoordinates.x + (next - center), y: currentCoordinates.y };
};

const collision: CollisionDetection = (args) => {
  const within = pointerWithin(args);
  return within.length > 0 ? within : rectIntersection(args);
};

function removeRecord(data: BoardColumnData | undefined, id: string): BoardColumnData | undefined {
  if (!data) return data;
  const found = data.pages.some((page) => page.items.some((item) => item.id === id));
  if (!found) return data;
  return {
    ...data,
    pages: data.pages.map((page) => ({
      ...page,
      items: page.items.filter((item) => item.id !== id),
      totalElements: Math.max(0, page.totalElements - 1),
    })),
  };
}

function insertRecord(data: BoardColumnData | undefined, record: RecordItem): BoardColumnData | undefined {
  if (!data) return data;
  return {
    ...data,
    pages: data.pages.map((page, index) => ({
      ...page,
      items: index === 0 ? [record, ...page.items.filter((item) => item.id !== record.id)] : page.items,
      totalElements: page.totalElements + 1,
    })),
  };
}

interface PendingMove {
  record: RecordItem;
  from: string;
  to: WorkflowState;
  transition: WorkflowTransitionDefinition;
}

export function PipelineBoard({
  module,
  capabilities,
  workflow,
}: {
  module: ModuleDefinition;
  capabilities: ModuleCapabilities;
  workflow: Workflow;
}) {
  const { orgId, access, isAdmin } = useOrg();
  const queryClient = useQueryClient();
  const { state: urlState, update } = useTableUrlState();
  const transition = useTransitionRecord(orgId, module.apiKey);
  const field = workflow.field;

  const states = React.useMemo(() => boardStates(workflow), [workflow]);
  const stateMap = React.useMemo(() => new Map(states.map((s) => [s.key, s])), [states]);
  const [collapsed, setCollapsed] = React.useState<Set<string>>(
    () => new Set(states.filter((s) => s.category === "LOST").map((s) => s.key))
  );
  const [active, setActive] = React.useState<CardDragData | null>(null);
  const [pending, setPending] = React.useState<PendingMove | null>(null);
  const lastDragEnd = React.useRef(0);
  const recentlyDragged = React.useCallback(() => Date.now() - lastDragEnd.current < 300, []);

  const params = React.useMemo(
    () => ({ q: urlState.q || undefined, filters: urlState.filters.length ? urlState.filters : undefined }),
    [urlState.q, urlState.filters]
  );

  const fields = React.useMemo(() => visibleFields(module, capabilities), [module, capabilities]);
  const filterFields = React.useMemo(
    () => filterableFields(module, capabilities).filter((f) => f.apiKey !== field),
    [module, capabilities, field]
  );
  const searchable = fields.some((f) => f.searchable);
  const titleField = displayField(module);
  const subtitleField = React.useMemo(() => {
    const key = module.settings?.subtitleField;
    return typeof key === "string" ? (fields.find((f) => f.apiKey === key) ?? null) : null;
  }, [module, fields]);
  const keyFields = React.useMemo(
    () =>
      fields
        .filter(
          (f) =>
            f.apiKey !== field &&
            f.apiKey !== titleField?.apiKey &&
            f.apiKey !== subtitleField?.apiKey &&
            !f.sensitive &&
            !isWideField(f) &&
            CARD_FIELD_TYPES.has(f.type)
        )
        .slice(0, 3),
    [fields, field, titleField, subtitleField]
  );

  const workflowField = activeFields(module).find((f) => f.apiKey === field);
  const fieldHidden = capabilities.hiddenFields.includes(field);
  const canDrag =
    capabilities.update && !fieldHidden && !capabilities.readOnlyFields.includes(field) && workflowField?.readOnly !== true;

  const [search, setSearch] = React.useState(urlState.q);
  const searchTimer = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  React.useEffect(() => () => {
    if (searchTimer.current) clearTimeout(searchTimer.current);
  }, []);
  const onSearch = (value: string) => {
    setSearch(value);
    if (searchTimer.current) clearTimeout(searchTimer.current);
    searchTimer.current = setTimeout(() => update({ q: value }), 300);
  };

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: columnCoordinates })
  );

  const targets = React.useMemo(
    () => (active ? allowedTargets(workflow, active.state, access, isAdmin) : new Map<string, WorkflowTransitionDefinition>()),
    [active, workflow, access, isAdmin]
  );

  /** Plain-text title for toasts and announcements (lookup titles fall back to the module name). */
  const title = (record: RecordItem) =>
    titleField?.type === "LOOKUP" ? `this ${module.labelSingular.toLowerCase()}` : recordTitle(module, record);

  const move = async ({ record, from, to }: PendingMove, comment?: string) => {
    const fromKey = queryKeys.boardColumn(orgId, module.apiKey, from, params);
    const toKey = queryKeys.boardColumn(orgId, module.apiKey, to.key, params);
    await Promise.all([queryClient.cancelQueries({ queryKey: fromKey }), queryClient.cancelQueries({ queryKey: toKey })]);
    const previousFrom = queryClient.getQueryData<BoardColumnData>(fromKey);
    const previousTo = queryClient.getQueryData<BoardColumnData>(toKey);
    const moved: RecordItem = { ...record, data: { ...record.data, [field]: to.key } };
    queryClient.setQueryData<BoardColumnData>(fromKey, (data) => removeRecord(data, record.id));
    queryClient.setQueryData<BoardColumnData>(toKey, (data) => insertRecord(data, moved));
    try {
      const result = await transition.mutateAsync({ id: record.id, to: to.key, comment, ifMatch: record.version });
      queryClient.setQueryData<BoardColumnData>(toKey, (data) =>
        data
          ? {
              ...data,
              pages: data.pages.map((page) => ({
                ...page,
                items: page.items.map((item) => (item.id === record.id ? result.record : item)),
              })),
            }
          : data
      );
      toast.success(`Moved to ${to.label}`);
      setPending(null);
    } catch {
      queryClient.setQueryData(fromKey, previousFrom);
      queryClient.setQueryData(toKey, previousTo);
      void queryClient.invalidateQueries({ queryKey: fromKey });
    }
  };

  const onDragStart = ({ active: dragged }: DragStartEvent) => {
    const data = dragged.data.current as CardDragData | undefined;
    if (data) setActive(data);
  };

  const onDragEnd = ({ over }: DragEndEvent) => {
    lastDragEnd.current = Date.now();
    const dragged = active;
    setActive(null);
    const targetKey = (over?.data.current as { state?: string } | undefined)?.state;
    if (!dragged || !targetKey || targetKey === dragged.state) return;
    const definition = allowedTargets(workflow, dragged.state, access, isAdmin).get(targetKey);
    const to = stateMap.get(targetKey);
    if (!definition || !to) {
      toast.error(`Can't move to ${to?.label ?? targetKey} from here`);
      return;
    }
    const next: PendingMove = { record: dragged.record, from: dragged.state, to, transition: definition };
    if (definition.requireComment) setPending(next);
    else void move(next);
  };

  const announcements: Announcements = {
    onDragStart: ({ active: dragged }) => {
      const data = dragged.data.current as CardDragData | undefined;
      if (!data) return "Picked up a card.";
      const count = allowedTargets(workflow, data.state, access, isAdmin).size;
      return `Picked up ${title(data.record)} in ${stateMap.get(data.state)?.label ?? data.state}. ${count} ${count === 1 ? "stage is" : "stages are"} available. Use the arrow keys to choose a stage.`;
    },
    onDragOver: ({ over }) => {
      const key = (over?.data.current as { state?: string } | undefined)?.state;
      return key ? `Over ${stateMap.get(key)?.label ?? key}.` : "Not over a stage.";
    },
    onDragEnd: ({ over }) => {
      const key = (over?.data.current as { state?: string } | undefined)?.state;
      return key ? `Dropped in ${stateMap.get(key)?.label ?? key}.` : "Dropped. The card did not move.";
    },
    onDragCancel: () => "Move cancelled. The card did not move.",
  };

  const toggleCollapsed = (key: string) =>
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2 px-4 sm:px-6 lg:px-8">
        {searchable ? (
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
          <FilterBuilder orgId={orgId} fields={filterFields} filters={urlState.filters} onApply={(filters) => update({ filters })} />
        ) : null}
        <FilterChips orgId={orgId} fields={filterFields} filters={urlState.filters} onChange={(filters) => update({ filters })} />
        <p className="ml-auto hidden text-xs text-muted-foreground md:block">
          {canDrag ? "Drag cards between stages, or focus a card's handle and press space." : "You can view this board but not move records."}
        </p>
      </div>

      <DndContext
        sensors={sensors}
        collisionDetection={collision}
        onDragStart={onDragStart}
        onDragEnd={onDragEnd}
        onDragCancel={() => {
          lastDragEnd.current = Date.now();
          setActive(null);
        }}
        accessibility={{
          announcements,
          screenReaderInstructions: {
            draggable:
              "To move a card, press space or enter on its handle, use the arrow keys to choose a stage, then press space or enter again to drop it. Press escape to cancel.",
          },
        }}
      >
        <div
          role="region"
          aria-label={`${module.labelPlural} board`}
          className="flex h-[calc(100dvh-13.5rem)] min-h-[26rem] items-stretch gap-3 overflow-x-auto px-4 pb-4 sm:px-6 lg:px-8"
        >
          {states.map((state) => (
            <BoardColumn
              key={state.key}
              orgId={orgId}
              module={module}
              field={field}
              state={state}
              params={params}
              drag={{ active: Boolean(active), valid: targets.has(state.key), source: active?.state === state.key }}
              collapsed={collapsed.has(state.key)}
              onToggleCollapsed={state.category === "LOST" ? () => toggleCollapsed(state.key) : undefined}
              subtitleField={subtitleField}
              keyFields={keyFields}
              canDrag={canDrag}
              recentlyDragged={recentlyDragged}
            />
          ))}
        </div>
        <DragOverlay dropAnimation={null}>
          {active ? (
            <div className="flex w-[17rem] rotate-[1.5deg] cursor-grabbing items-start rounded-lg border border-primary/40 bg-card p-2.5 shadow-lg">
              <CardBody orgId={orgId} module={module} record={active.record} subtitleField={subtitleField} keyFields={keyFields} />
            </div>
          ) : null}
        </DragOverlay>
      </DndContext>

      {pending ? (
        <TransitionDialog
          open
          onOpenChange={(open) => !open && setPending(null)}
          title={pending.transition.label}
          subject={<RecordTitle orgId={orgId} module={module} record={pending.record} />}
          target={pending.to}
          requireComment
          pending={transition.isPending}
          onConfirm={(comment) => move(pending, comment)}
        />
      ) : null}
    </div>
  );
}
