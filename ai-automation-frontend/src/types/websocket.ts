import type { AgenticState, AgenticIteration, AgenticPhase, HumanInputField } from './index';

// ── Message types ────────────────────────────────────────────────────────

export interface StepMessage {
  stepIndex: number;
  status: string;
  input: string;
  output: string;
  durationMs: number;
  blockType?: string;
  blockName?: string;
}

export interface StatusMessage {
  type: 'status';
  runId: string;
  status: string;
  stepsCompleted?: number;
}

export interface ApprovalRequiredMessage {
  type: 'approval_required';
  blockId: string;
  question: string;
  options: string[];
  /** the options that count as a rejection; missing from older backends */
  rejectOptions?: string[];
  stepIndex: number;
}

export interface ApprovalResolvedMessage {
  type: 'approval_resolved';
  stepIndex: number;
  answer: string;
}

export interface HumanInputRequiredMessage {
  type: 'human_input_required';
  blockId: string;
  prompt: string;
  inputType: 'text' | 'number' | 'textarea' | 'select';
  options: string[];
  defaultValue: string;
  required: boolean;
  validationPattern: string | null;
  variableName: string;
  stepIndex: number;
  /** Form with several fields; only sent when the block defines `fields` (ec913801). */
  fields?: HumanInputField[];
}

export interface HumanInputResolvedMessage {
  type: 'human_input_resolved';
  stepIndex: number;
  answer: string;
}

// Agentic message types
export interface AgenticStartedMessage {
  type: 'agentic_started';
  stepIndex: number;
  goal: string;
  maxIterations: number;
}

export interface AgenticReasoningMessage {
  type: 'agentic_reasoning';
  stepIndex: number;
  iteration: number;
}

export interface AgenticReasonedMessage {
  type: 'agentic_reasoned';
  stepIndex: number;
  iteration: number;
  reasoning: string;
}

export interface AgenticActingMessage {
  type: 'agentic_acting';
  stepIndex: number;
  iteration: number;
  action: string;
}

export interface AgenticActedMessage {
  type: 'agentic_acted';
  stepIndex: number;
  iteration: number;
  action: string;
  output: string;
  exitCode: number;
}

export interface AgenticGoalAchievedMessage {
  type: 'agentic_goal_achieved' | 'agentic_goal_impossible';
  stepIndex: number;
  iteration: number;
  summary: string;
}

export interface AgenticCompletedMessage {
  type: 'agentic_completed';
  stepIndex: number;
  goalAchieved: boolean;
  totalIterations: number;
  durationMs: number;
}

export interface AgenticErrorMessage {
  type: 'agentic_error';
  stepIndex: number;
  iteration: number;
  phase: string;
  error: string;
}

export interface AgenticApprovalMessage {
  type: 'agentic_approval_required';
  stepIndex: number;
  iteration: number;
  action: string;
  rationale: string;
}

export type AgenticMessage =
  | AgenticStartedMessage
  | AgenticReasoningMessage
  | AgenticReasonedMessage
  | AgenticActingMessage
  | AgenticActedMessage
  | AgenticGoalAchievedMessage
  | AgenticCompletedMessage
  | AgenticErrorMessage
  | AgenticApprovalMessage;

export interface ForEachItemStartedMessage {
  type: 'foreach_item_started';
  stepIndex: number;
  itemIndex: number;
  item: string;
  totalItems: number;
}

export interface ForEachItemCompletedMessage {
  type: 'foreach_item_completed';
  stepIndex: number;
  itemIndex: number;
  item: string;
  totalItems: number;
  status: string;
  output: string;
  /** foreach with workflow_id: the child run of this item ('' when none started; absent from older backends). */
  subRunId?: string;
}

export type ForEachMessage = ForEachItemStartedMessage | ForEachItemCompletedMessage;

export interface ForEachItemState {
  itemIndex: number;
  item: string;
  status: 'running' | 'completed' | 'failed';
  output?: string;
  /** foreach with workflow_id: the child run of this item (TODO a5c9fbbf). */
  subRunId?: string;
}

export interface ForEachState {
  stepIndex: number;
  totalItems: number;
  completedItems: number;
  items: ForEachItemState[];
}

// Live output of running blocks (batched by the backend, see RunLiveLogService)
export type LiveLineKind = 'line' | 'text' | 'tool' | 'tool_result' | 'tool_error' | 'result' | 'system';

export interface LiveLine {
  seq: number;
  stepIndex: number;
  kind: LiveLineKind;
  text: string;
  /** epoch millis */
  ts: number;
}

export interface LiveOutputMessage {
  type: 'live_output';
  runId: string;
  bufferId: string;
  lines: LiveLine[];
  skipped: number;
}

/** Response of GET /executions/{id}/live-log */
export interface LiveLogSnapshot {
  runId: string;
  bufferId: string | null;
  available: boolean;
  firstSeq: number;
  lastSeq: number;
  skipped: number;
  /** Block names of steps with buffered lines (keys are step indices). */
  stepNames?: Record<string, string>;
  lines: LiveLine[];
  /** Served from the stored log after the in-memory buffer was evicted (missing on older backends). */
  stored?: boolean;
}

/** Where the replayed live log came from: not asked yet, the in-memory buffer, the stored log, or nowhere. */
export type LiveLogSource = 'pending' | 'memory' | 'stored' | 'none';

export interface CommandMessage {
  type: 'command';
  runId: string;
  input: string;
  output: string;
  timestamp?: string;
}

export interface ContainerMessage {
  type: 'container_starting' | 'container_started' | 'container_skipped';
  runId: string;
  message?: string;
  containerId?: string;
  timestamp?: string;
}

export type WsMessage = StepMessage | StatusMessage | ApprovalRequiredMessage | ApprovalResolvedMessage | HumanInputRequiredMessage | HumanInputResolvedMessage | AgenticMessage | ForEachMessage | LiveOutputMessage | CommandMessage | ContainerMessage;

export interface PendingApproval {
  blockId: string;
  question: string;
  options: string[];
  /** the options that count as a rejection; undefined = old backend, then only "No" is one */
  rejectOptions?: string[];
  stepIndex: number;
}

// ── Type guards ──────────────────────────────────────────────────────────

export function isStatusMessage(data: WsMessage): data is StatusMessage {
  return 'type' in data && data.type === 'status';
}

export function isApprovalRequired(data: WsMessage): data is ApprovalRequiredMessage {
  return 'type' in data && data.type === 'approval_required';
}

export function isApprovalResolved(data: WsMessage): data is ApprovalResolvedMessage {
  return 'type' in data && data.type === 'approval_resolved';
}

export function isHumanInputRequired(data: WsMessage): data is HumanInputRequiredMessage {
  return 'type' in data && data.type === 'human_input_required';
}

export function isHumanInputResolved(data: WsMessage): data is HumanInputResolvedMessage {
  return 'type' in data && data.type === 'human_input_resolved';
}

export function isAgenticMessage(data: WsMessage): data is AgenticMessage {
  return 'type' in data && typeof data.type === 'string' && data.type.startsWith('agentic_');
}

export function isForEachMessage(data: WsMessage): data is ForEachMessage {
  return 'type' in data && typeof data.type === 'string' && data.type.startsWith('foreach_');
}

/** Step placeholder or finished step — the only message without a `type`. */
export function isStepMessage(data: WsMessage): data is StepMessage {
  return !('type' in data) && typeof (data as StepMessage).stepIndex === 'number';
}

export function isLiveOutputMessage(data: WsMessage): data is LiveOutputMessage {
  return 'type' in data && data.type === 'live_output';
}

export function isCommandMessage(data: WsMessage): data is CommandMessage {
  return 'type' in data && data.type === 'command';
}

export function isContainerMessage(data: WsMessage): data is ContainerMessage {
  return 'type' in data && typeof data.type === 'string' && data.type.startsWith('container_');
}

// ── Reducer functions ────────────────────────────────────────────────────

export function handleForEachMessage(
  msg: ForEachMessage,
  setForEachStates: React.Dispatch<React.SetStateAction<Map<number, ForEachState>>>,
) {
  setForEachStates((prev) => {
    const updated = new Map(prev);
    const current = updated.get(msg.stepIndex) || {
      stepIndex: msg.stepIndex,
      totalItems: msg.totalItems,
      completedItems: 0,
      items: [],
    };

    if (msg.type === 'foreach_item_started') {
      const item: ForEachItemState = { itemIndex: msg.itemIndex, item: msg.item, status: 'running' };
      updated.set(msg.stepIndex, {
        ...current,
        totalItems: msg.totalItems,
        items: [...current.items.filter((i) => i.itemIndex !== msg.itemIndex), item],
      });
    } else if (msg.type === 'foreach_item_completed') {
      const items = current.items.map((i) =>
        i.itemIndex === msg.itemIndex
          ? {
            ...i,
            status: (msg.status === 'FAILED' ? 'failed' : 'completed') as ForEachItemState['status'],
            output: msg.output,
            ...(msg.subRunId ? { subRunId: msg.subRunId } : {}),
          }
          : i,
      );
      updated.set(msg.stepIndex, {
        ...current,
        totalItems: msg.totalItems,
        completedItems: items.filter((i) => i.status !== 'running').length,
        items,
      });
    }

    return updated;
  });
}

export function handleAgenticMessage(
  msg: AgenticMessage,
  setAgenticStates: React.Dispatch<React.SetStateAction<Map<number, AgenticState>>>,
) {
  const stepIndex = msg.stepIndex;

  setAgenticStates((prev) => {
    const updated = new Map(prev);
    const current = updated.get(stepIndex) || {
      stepIndex,
      goal: '',
      maxIterations: 0,
      currentIteration: 0,
      iterations: [],
      goalAchieved: null,
    };

    switch (msg.type) {
      case 'agentic_started':
        updated.set(stepIndex, {
          ...current,
          goal: msg.goal,
          maxIterations: msg.maxIterations,
        });
        break;

      case 'agentic_reasoning': {
        const iter: AgenticIteration = {
          iteration: msg.iteration,
          phase: 'reasoning',
          timestamp: new Date().toISOString(),
        };
        updated.set(stepIndex, {
          ...current,
          currentIteration: msg.iteration,
          iterations: [...current.iterations.filter((i) => i.iteration !== msg.iteration), iter],
        });
        break;
      }

      case 'agentic_reasoned': {
        const iters = current.iterations.map((i) =>
          i.iteration === msg.iteration ? { ...i, reasoning: msg.reasoning, phase: 'acting' as AgenticPhase } : i,
        );
        updated.set(stepIndex, { ...current, iterations: iters });
        break;
      }

      case 'agentic_acting': {
        const iters = current.iterations.map((i) =>
          i.iteration === msg.iteration ? { ...i, action: msg.action, phase: 'acting' as AgenticPhase } : i,
        );
        updated.set(stepIndex, { ...current, iterations: iters });
        break;
      }

      case 'agentic_acted': {
        const iters = current.iterations.map((i) =>
          i.iteration === msg.iteration
            ? { ...i, output: msg.output, exitCode: msg.exitCode, action: msg.action, phase: 'acted' as AgenticPhase }
            : i,
        );
        updated.set(stepIndex, { ...current, iterations: iters });
        break;
      }

      case 'agentic_goal_achieved':
      case 'agentic_goal_impossible':
        updated.set(stepIndex, {
          ...current,
          goalAchieved: msg.type === 'agentic_goal_achieved',
          finalSummary: msg.summary,
        });
        break;

      case 'agentic_completed':
        updated.set(stepIndex, {
          ...current,
          goalAchieved: msg.goalAchieved,
          currentIteration: msg.totalIterations,
        });
        break;

      case 'agentic_error': {
        const iter: AgenticIteration = {
          iteration: msg.iteration,
          phase: 'error',
          output: msg.error,
          timestamp: new Date().toISOString(),
        };
        const existingIters = current.iterations.filter((i) => i.iteration !== msg.iteration);
        updated.set(stepIndex, { ...current, iterations: [...existingIters, iter] });
        break;
      }

      case 'agentic_approval_required': {
        const iters = current.iterations.map((i) =>
          i.iteration === msg.iteration ? { ...i, phase: 'approval' as AgenticPhase, action: msg.action } : i,
        );
        updated.set(stepIndex, { ...current, iterations: iters });
        break;
      }
    }

    return updated;
  });
}
