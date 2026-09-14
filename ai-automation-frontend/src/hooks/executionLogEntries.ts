import type { LiveLine, LiveLineKind, WsMessage } from '../types/websocket';
import {
  isAgenticMessage, isApprovalRequired, isApprovalResolved, isCommandMessage, isContainerMessage,
  isForEachMessage, isHumanInputRequired, isHumanInputResolved, isStatusMessage, isStepMessage,
} from '../types/websocket';

/**
 * Pure mapping from execution WebSocket messages to log viewer entries.
 * Kept out of useExecutionWebSocket so the texts are testable without a STOMP client.
 */

export const MAX_LOG_ENTRIES = 2000;

export type LogLevel = 'INFO' | 'WARN' | 'ERROR';

export interface ExecutionLogEntry {
  /** Stable React key: `live-<bufferId>-<seq>` for live lines, `evt-<n>` for events. */
  id: string;
  timestamp: string;
  level: LogLevel;
  message: string;
  /** Set for streamed block output. */
  kind?: LiveLineKind;
  stepIndex?: number;
  stepLabel?: string;
  /** Set on the marker entry for lines that were not received (buffer overflow, late join). */
  skipped?: number;
}

/** Mutable per-run lookup the mapping reads and fills: block names and steps with live output. */
export interface StepLogContext {
  labels: Map<number, string>;
  liveSteps: Set<number>;
}

export function createStepLogContext(): StepLogContext {
  return { labels: new Map(), liveSteps: new Set() };
}

export function stepLabel(stepIndex: number, ctx: StepLogContext): string {
  const name = ctx.labels.get(stepIndex);
  return name ? `Step ${stepIndex} · ${name}` : `Step ${stepIndex}`;
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const seconds = Math.round(ms / 1000);
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

/** Maps a non-live message to one log entry. Records block names of step messages in `ctx`. */
export function eventToEntry(
  data: WsMessage,
  id: string,
  ctx: StepLogContext,
  now: string = new Date().toISOString(),
): ExecutionLogEntry {
  const entry = (level: LogLevel, message: string, extra: Partial<ExecutionLogEntry> = {}): ExecutionLogEntry =>
    ({ id, timestamp: now, level, message, ...extra });

  if (isForEachMessage(data)) {
    const pct = data.type === 'foreach_item_completed'
      ? `${Math.round(((data.itemIndex + 1) / data.totalItems) * 100)}%`
      : '';
    const status = data.type === 'foreach_item_completed' ? ` [${data.status}]` : '';
    return entry(
      data.type === 'foreach_item_completed' && data.status === 'FAILED' ? 'ERROR' : 'INFO',
      data.type === 'foreach_item_started'
        ? `[forEach] Starting item ${data.itemIndex + 1}/${data.totalItems}: ${data.item}`
        : `[forEach] ${data.itemIndex + 1}/${data.totalItems} (${pct})${status}: ${data.item}`,
    );
  }
  if (isAgenticMessage(data)) {
    const message =
      data.type === 'agentic_started' ? `[Agentic] Goal: ${data.goal}`
      : data.type === 'agentic_reasoned' ? `[Agentic #${data.iteration}] Reasoning complete`
      : data.type === 'agentic_acted' ? `[Agentic #${data.iteration}] Action executed (exit: ${data.exitCode})`
      : data.type === 'agentic_goal_achieved' ? `[Agentic] Goal achieved: ${data.summary}`
      : data.type === 'agentic_goal_impossible' ? `[Agentic] Goal impossible: ${data.summary}`
      : data.type === 'agentic_completed' ? `[Agentic] Completed (${data.totalIterations} iterations, goal: ${data.goalAchieved})`
      : data.type === 'agentic_error' ? `[Agentic #${data.iteration}] Error: ${data.error}`
      : `[Agentic] ${data.type}`;
    return entry(data.type === 'agentic_error' ? 'ERROR' : 'INFO', message);
  }
  if (isApprovalRequired(data)) {
    return entry('WARN', `[Step ${data.stepIndex}] Approval required: ${data.question}`);
  }
  if (isApprovalResolved(data)) {
    return entry('INFO', `[Step ${data.stepIndex}] Approval resolved: ${data.answer}`);
  }
  if (isHumanInputRequired(data)) {
    return entry('WARN', `[Step ${data.stepIndex}] Human input required: ${data.prompt}`);
  }
  if (isHumanInputResolved(data)) {
    return entry('INFO', `[Step ${data.stepIndex}] Human input received: ${data.answer}`);
  }
  if (isStatusMessage(data)) {
    return entry('INFO',
      `Run status: ${data.status}${data.stepsCompleted != null ? ` (${data.stepsCompleted} steps completed)` : ''}`);
  }
  if (isCommandMessage(data)) {
    return entry('INFO', `$ ${data.input}${data.output ? `\n${data.output}` : ''}`,
      { timestamp: data.timestamp ?? now });
  }
  if (isContainerMessage(data)) {
    return entry(data.type === 'container_skipped' ? 'WARN' : 'INFO', `[Container] ${data.message ?? data.type}`,
      { timestamp: data.timestamp ?? now });
  }
  if (isStepMessage(data)) {
    if (data.blockName) ctx.labels.set(data.stepIndex, data.blockName);
    const label = stepLabel(data.stepIndex, ctx);
    const level: LogLevel = data.status === 'FAILED' ? 'ERROR' : 'INFO';
    const extra = { stepIndex: data.stepIndex, stepLabel: label };
    if (data.status === 'RUNNING' && !data.output) {
      return entry('INFO', `[${label}] started`, extra);
    }
    if (ctx.liveSteps.has(data.stepIndex)) {
      // The output already streamed line by line — only close the step.
      return entry(level, `[${label}] ${data.status}${data.durationMs ? ` (${formatDuration(data.durationMs)})` : ''}`, extra);
    }
    return entry(level, `[${label}] ${data.output || data.input}`, extra);
  }
  return entry('INFO', JSON.stringify(data));
}

/** Maps streamed lines to entries and marks their steps as live in `ctx`. */
export function liveLinesToEntries(bufferId: string, lines: LiveLine[], ctx: StepLogContext): ExecutionLogEntry[] {
  return lines.map((line) => {
    ctx.liveSteps.add(line.stepIndex);
    return {
      id: `live-${bufferId}-${line.seq}`,
      timestamp: new Date(line.ts).toISOString(),
      level: line.kind === 'tool_error' ? 'ERROR' : 'INFO',
      message: line.text,
      kind: line.kind,
      stepIndex: line.stepIndex,
      stepLabel: stepLabel(line.stepIndex, ctx),
    };
  });
}

export function skippedMarker(bufferId: string, beforeSeq: number, count: number): ExecutionLogEntry {
  return {
    id: `skip-${bufferId}-${beforeSeq}`,
    timestamp: new Date().toISOString(),
    level: 'WARN',
    message: `… ${count} lines skipped`,
    skipped: count,
  };
}

export function appendCapped<T>(prev: T[], next: T[], max: number = MAX_LOG_ENTRIES): T[] {
  if (next.length === 0) return prev;
  const merged = prev.concat(next);
  return merged.length > max ? merged.slice(merged.length - max) : merged;
}
