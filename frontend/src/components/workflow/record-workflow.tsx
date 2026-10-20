"use client";

import * as React from "react";
import { toast } from "sonner";
import { ArrowRight, ChevronDown, GitBranch, MessageSquareText } from "lucide-react";

import { StateBadge } from "@/components/workflow/state-badge";
import { TransitionDialog } from "@/components/workflow/transition-dialog";
import { ErrorState } from "@/components/common/error-state";
import { Avatar, AvatarFallback, initials } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";
import { SimpleTooltip } from "@/components/ui/tooltip";
import {
  ApiError,
  useAvailableTransitions,
  useMembers,
  useTransitionHistory,
  useTransitionRecord,
  type VersionedRecord,
} from "@/lib/api";
import { formatDateTime, formatRelative, shortId } from "@/lib/metadata/format";
import { stateByKey } from "@/lib/workflow";
import type { AvailableTransition, Workflow, WorkflowState } from "@/types/workflow";

function asState(transition: AvailableTransition): WorkflowState {
  return { key: transition.to, label: transition.toLabel, category: transition.category, color: transition.color };
}

/** "Move to…" menu built from `transitions/available`; prompts for a comment when the transition requires one. */
export function WorkflowMoveMenu({
  orgId,
  moduleKey,
  record,
  workflow,
  subject,
}: {
  orgId: string;
  moduleKey: string;
  record: VersionedRecord;
  workflow: Workflow;
  subject?: React.ReactNode;
}) {
  const available = useAvailableTransitions(orgId, moduleKey, record.id, { enabled: !record.deletedAt });
  const transition = useTransitionRecord(orgId, moduleKey);
  const [pending, setPending] = React.useState<AvailableTransition | null>(null);
  const transitions = available.data?.transitions ?? [];

  const move = async (target: AvailableTransition, comment?: string) => {
    try {
      await transition.mutateAsync({ id: record.id, to: target.to, comment, ifMatch: record.etag });
      toast.success(`Moved to ${target.toLabel}`);
      setPending(null);
    } catch (error) {
      if (error instanceof ApiError && error.isVersionConflict) void available.refetch();
    }
  };

  if (record.deletedAt) return null;
  if (available.isLoading) return <Skeleton className="h-9 w-28 rounded-md" />;
  if (transitions.length === 0) {
    const current = stateByKey(workflow, available.data?.currentState ?? null);
    return (
      <SimpleTooltip label={current ? `No moves from ${current.label} are available to you` : "No moves are available to you"}>
        <span tabIndex={0} className="focus-ring rounded-md">
          <Button variant="outline" disabled>
            <GitBranch /> Move to…
          </Button>
        </span>
      </SimpleTooltip>
    );
  }

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" disabled={transition.isPending}>
            <GitBranch /> Move to… <ChevronDown className="size-3.5 opacity-60" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-60">
          <DropdownMenuLabel>Move this record</DropdownMenuLabel>
          {transitions.map((item) => (
            <DropdownMenuItem
              key={item.key}
              onSelect={() => (item.requireComment ? setPending(item) : void move(item))}
              className="justify-between gap-3"
            >
              <span className="truncate">{item.label}</span>
              <span className="flex items-center gap-1.5">
                {item.requireComment ? <MessageSquareText className="size-3.5 text-muted-foreground" aria-label="Needs a comment" /> : null}
                <StateBadge state={asState(item)} />
              </span>
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
      {pending ? (
        <TransitionDialog
          open
          onOpenChange={(open) => !open && setPending(null)}
          title={pending.label}
          subject={subject}
          target={asState(pending)}
          requireComment={pending.requireComment}
          pending={transition.isPending}
          onConfirm={(comment) => move(pending, comment)}
        />
      ) : null}
    </>
  );
}

/** Stage history of a record (newest first), with the actor and comment of every move. */
export function TransitionTimeline({ orgId, moduleKey, recordId, workflow }: {
  orgId: string;
  moduleKey: string;
  recordId: string;
  workflow: Workflow;
}) {
  const history = useTransitionHistory(orgId, moduleKey, recordId);
  const members = useMembers(orgId);

  const actor = (userId?: string | null) => {
    if (!userId) return "System";
    const member = members.data?.get(userId);
    return member?.displayName ?? member?.email ?? `User ${shortId(userId)}`;
  };
  const stateLabel = (key: string | null) => {
    if (!key) return null;
    const state = stateByKey(workflow, key);
    return state ? <StateBadge state={state} /> : <Badge variant="outline">{key}</Badge>;
  };

  if (history.isLoading) {
    return (
      <div className="flex flex-col gap-3">
        {Array.from({ length: 3 }, (_, i) => (
          <Skeleton key={i} className="h-10 w-full" />
        ))}
      </div>
    );
  }
  if (history.isError) return <ErrorState size="sm" error={history.error} onRetry={() => void history.refetch()} />;
  const items = history.data?.items ?? [];
  if (items.length === 0) return <p className="text-[13px] text-muted-foreground">No stage changes yet.</p>;

  return (
    <ol aria-label="Stage history" className="relative flex flex-col gap-4 before:absolute before:bottom-1 before:left-[11px] before:top-1 before:w-px before:bg-border">
      {items.map((item) => {
        const name = actor(item.actorUserId);
        return (
          <li key={item.id} className="relative flex gap-2.5">
            <Avatar className="relative z-[1] size-6 ring-4 ring-card">
              <AvatarFallback className="text-[9px]">{initials(name)}</AvatarFallback>
            </Avatar>
            <div className="min-w-0 flex-1 text-[13px]">
              <div className="flex flex-wrap items-center gap-1.5">
                {item.fromState ? (
                  <>
                    {stateLabel(item.fromState)}
                    <ArrowRight className="size-3 text-subtle-foreground" aria-label="to" />
                  </>
                ) : null}
                {stateLabel(item.toState)}
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                {item.fromState ? name : `Created by ${name}`} ·{" "}
                <SimpleTooltip label={formatDateTime(item.createdAt)}>
                  <span>{formatRelative(item.createdAt)}</span>
                </SimpleTooltip>
              </p>
              {item.comment ? (
                <p className="mt-1.5 whitespace-pre-wrap rounded-md border border-border bg-surface px-2.5 py-1.5 text-[13px] leading-relaxed">
                  {item.comment}
                </p>
              ) : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
