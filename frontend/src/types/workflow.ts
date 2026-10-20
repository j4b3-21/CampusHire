import type { Instant, Uuid } from "@/types/api";
import type { RecordItem } from "@/types/records";

export const WORKFLOW_CATEGORIES = ["OPEN", "IN_PROGRESS", "WON", "LOST"] as const;

export type WorkflowCategory = (typeof WORKFLOW_CATEGORIES)[number];

export interface WorkflowState {
  /** Option value of the workflow field. */
  key: string;
  label: string;
  category: WorkflowCategory;
  /** Presentation hint: a palette name such as `emerald` or a hex color. */
  color?: string | null;
}

export interface WorkflowTransitionDefinition {
  key: string;
  label: string;
  from: string[];
  to: string;
  /** Org role keys, or `["*"]` for every member. */
  roles: string[];
  requireComment?: boolean;
}

export interface WorkflowDefinitionBody {
  initial: string;
  states: WorkflowState[];
  transitions: WorkflowTransitionDefinition[];
}

/** `GET /orgs/{orgId}/modules/{key}/workflow` (ETag = version). */
export interface Workflow {
  id: Uuid;
  module: string;
  field: string;
  version: number;
  definition: WorkflowDefinitionBody;
  createdAt: Instant;
  createdBy?: Uuid | null;
  updatedAt: Instant;
  updatedBy?: Uuid | null;
}

export interface AvailableTransition {
  key: string;
  label: string;
  to: string;
  toLabel: string;
  category: WorkflowCategory;
  color?: string | null;
  requireComment: boolean;
}

/** `GET .../records/{id}/transitions/available`. */
export interface AvailableTransitions {
  recordId: Uuid;
  version: number;
  field: string;
  currentState: string | null;
  transitions: AvailableTransition[];
}

/** One entry of `GET .../records/{id}/transitions` (newest first). */
export interface TransitionHistoryItem {
  id: Uuid;
  recordVersion: number;
  fromState: string | null;
  toState: string;
  transitionKey?: string | null;
  comment?: string | null;
  actorUserId?: Uuid | null;
  createdAt: Instant;
}

export interface TransitionOutcome {
  record: RecordItem;
  transition: TransitionHistoryItem;
}

export interface BulkTransitionItem {
  recordId: Uuid;
  ok: boolean;
  version?: number;
  error?: { code: string; message: string };
}

export interface BulkTransitionResult {
  results: BulkTransitionItem[];
  succeeded: number;
  failed: number;
}
