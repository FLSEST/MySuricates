/**
 * What a finished run "resulted in", for the Simple run page: the validated report, the output
 * worth reading, the files it names and a plain-text error. Pure functions over the run as the
 * API delivers it — no extra request per run.
 */
import { PATH_REGEX } from '../components/shared/PathLinkedOutput';
import { parseWorkflowResult, type WorkflowResult } from '../components/dashboard/workflowWidgetContract';
import { latestStepByBlock, stepBlockName, stepBlockType } from '../components/workflow/stepMetadata';
import type { StepResult, WorkflowRun } from '../types';

/** The run's validated `dashboard_widget` contract (written by a dashboard-report block or a marker), else null. */
export function readRunReport(run: Pick<WorkflowRun, 'metadata'>): WorkflowResult | null {
  return parseWorkflowResult(run.metadata?.dashboard_widget);
}

/** True when the run tried to report (a marker existed) but the report did not match the contract. */
export function reportInvalid(run: Pick<WorkflowRun, 'metadata'>): boolean {
  const metadata = run.metadata ?? {};
  if (metadata.dashboard_widget_error === 'INVALID') return true;
  return metadata.dashboard_widget != null && readRunReport(run) === null;
}

/**
 * Blocks whose output is not "the result": people's answers, TODO bookkeeping, the report block
 * (shown as report) and compression (a shortened copy of another step's output).
 */
const NON_RESULT_BLOCK_TYPES: ReadonlySet<string> = new Set([
  'approval', 'human-input', 'todo-create', 'todo-update', 'todo-close',
  'dashboard-report', 'compress', 'compress-ai',
]);

/**
 * The step whose output best answers "what came out": the newest attempt per block, completed,
 * with readable output once the marker lines are gone, not derived from another step and not of a
 * bookkeeping block type — the last such step in run order. Null when there is none.
 */
export function pickResultStep(steps: readonly StepResult[]): StepResult | null {
  let picked: StepResult | null = null;
  for (const step of latestStepByBlock(steps).values()) {
    if (step.status !== 'COMPLETED') continue;
    if (step.metadata?.derivedOutput === true) continue;
    if (NON_RESULT_BLOCK_TYPES.has(stepBlockType(step))) continue;
    if (!stripMarkerLines(step.output ?? '')) continue;
    if (!picked || step.stepIndex > picked.stepIndex) picked = step;
  }
  return picked;
}

// Mirrors the backend parsers (GateStatusOutputParser, TodoOutputParser, DashboardWidgetOccurrences):
// line-anchored, an optional foreach prefix "[item]: ", an optional list/quote/number prefix and
// heading, bold/backtick decoration, underscore or hyphen spelling, any case.
const MARKER_LINE = new RegExp(
  '^[ \\t]*(?:\\[[^\\]\\n]*\\]:\\s*)?(?:[-*>+]\\s+|\\d{1,2}[.)]\\s+)?(?:#{1,6}\\s+)?[*`]*'
  + '(gate[_-](?:status|category|description)|todo[_-]item|dashboard[_-]widget)[*`]*:(.*)$',
  'i',
);

/** Net `{`/`[` minus `}`/`]` of a text, ignoring brackets inside JSON strings. */
function bracketDepth(text: string, start: { depth: number; inString: boolean }) {
  let { depth, inString } = start;
  let escaped = false;
  for (const char of text) {
    if (inString) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === '"') inString = false;
    } else if (char === '"') inString = true;
    else if (char === '{' || char === '[') depth++;
    else if (char === '}' || char === ']') depth--;
  }
  return { depth, inString };
}

/**
 * Removes the machine marker lines (`gate_status:`, `gate_category:`, `gate_description:`,
 * `todo_item:`, `dashboard_widget:`) — they already show as banner, report or TODO count. A
 * `dashboard_widget` JSON value that continues over several lines goes with its marker. Runs of
 * blank lines left behind are collapsed; the result is trimmed.
 */
export function stripMarkerLines(text: string): string {
  const kept: string[] = [];
  let json: { depth: number; inString: boolean } | null = null;
  for (const line of text.split('\n')) {
    if (json) {
      json = bracketDepth(line, json);
      if (json.depth <= 0) json = null;
      continue;
    }
    const match = MARKER_LINE.exec(line);
    if (!match) {
      kept.push(line);
      continue;
    }
    if (match[1]!.toLowerCase().startsWith('dashboard')) {
      const state = bracketDepth(match[2] ?? '', { depth: 0, inString: false });
      if (state.depth > 0) json = state;
    }
  }
  return kept.join('\n').replace(/\n{3,}/g, '\n\n').trim();
}

/** The step the run failed at: the step `run.error.stepIndex` names, else the last FAILED step. */
export function failedStep(run: Pick<WorkflowRun, 'error' | 'steps'>): StepResult | null {
  const index = run.error?.stepIndex;
  const steps = [...run.steps].sort((a, b) => a.stepIndex - b.stepIndex);
  if (typeof index === 'number') {
    const named = steps.filter((step) => step.stepIndex === index).pop();
    if (named) return named;
  }
  return steps.filter((step) => step.status === 'FAILED').pop() ?? null;
}

interface ErrorTexts { simple: { result: { errorFallback: string } } }

/**
 * Plain-text reason a run failed — never JSON: the run's error message, else the failed step's
 * error, else a generic sentence.
 */
export function runErrorText(run: Pick<WorkflowRun, 'error' | 'steps'>, t: ErrorTexts): string {
  const message = run.error?.message;
  if (typeof message === 'string' && message.trim()) return message.trim();
  const stepError = failedStep(run)?.error;
  if (typeof stepError === 'string' && stepError.trim()) return stepError.trim();
  return t.simple.result.errorFallback;
}

interface StepNameTexts {
  workflows: {
    blockTypes: Record<string, string>;
    execution: { stepNumber: string };
  };
}

/** A name for a step a newcomer can read: block name, else translated block type, else "Step n". */
export function stepDisplayName(step: StepResult, t: StepNameTexts): string {
  const name = stepBlockName(step);
  if (name) return name;
  const type = stepBlockType(step);
  const typeLabel = type ? t.workflows.blockTypes[type] : undefined;
  return typeLabel ?? t.workflows.execution.stepNumber.replace('{n}', String(step.stepIndex + 1));
}

/** Characters that mean a PATH_REGEX match is only the tail of something else (URL, `~/…`, relative path). */
const NOT_A_PATH_START = /[\w.:/~-]/;

/** At most this many paths are offered — beyond that the output itself is the better list. */
export const MAX_RESULT_PATHS = 10;

/** Absolute file paths named in a text, unique, in order of appearance; URLs and relative paths excluded. */
export function extractPaths(text: string): string[] {
  const paths: string[] = [];
  for (const match of text.matchAll(PATH_REGEX)) {
    const before = match.index > 0 ? text[match.index - 1]! : '';
    if (before && NOT_A_PATH_START.test(before)) continue;
    const path = (match[1] ?? match[0]).replace(/\.+$/, '');
    if (path.endsWith('/') || paths.includes(path)) continue;
    paths.push(path);
    if (paths.length === MAX_RESULT_PATHS) break;
  }
  return paths;
}

/** Number of TODOs the run's `todo_item` markers created (`metadata.todo_items`). */
export function createdTodoCount(run: Pick<WorkflowRun, 'metadata'>): number {
  const items = run.metadata?.todo_items;
  return Array.isArray(items) ? items.length : 0;
}
