/**
 * The contract a workflow result widget may show (spec E3) — the browser-side mirror of the
 * backend's DashboardWidgetResultParser, the way sanitizeConfig mirrors DashboardLayoutCanonicalizer.
 *
 * Workflow output is AI-generated text from external sources (prompt injection is possible) and the
 * API key lives in this origin's localStorage. The contract is therefore closed: known fields only,
 * capped lengths and counts, plain strings — and it holds no id and no URL, so nothing inside it can
 * ever become a link or a navigation target. Anything that does not match is rejected as a whole
 * (null), and the widget shows its neutral state instead.
 *
 * Markup is not filtered: '<script>' stays ordinary text here and is harmless because widgets render
 * these values only as React text nodes. What is rejected are characters that make text read
 * differently than it looks — control, format (zero width, BOM, bidi overrides), separators,
 * surrogates and private use, plus (mirroring the backend's hardening round) every space-like
 * separator except the ordinary U+0020 and a denylist of invisible filler letters/symbols that are
 * not classified as separators at all.
 */

import type { ResolvedWidget } from '../../hooks/useDashboardLayout';
import type { RunStatus } from '../../types';

export const WORKFLOW_STATUS_WIDGET_ID = 'workflow-status';
export const WORKFLOW_RESULT_WIDGET_ID = 'workflow-result';

/** Never ask the backend about more workflows than it accepts (DashboardWorkflowService.MAX_WORKFLOWS). */
export const MAX_WORKFLOW_WIDGET_IDS = 50;

/** Same numbers as DashboardWidgetResultParser. */
export const WORKFLOW_RESULT_CAPS = {
  title: 80, kpiText: 40, item: 200, summary: 500, kpis: 6, items: 10,
} as const;

/** Own cap for the workflow name shown as a widget heading — not part of the AI-written contract. */
const MAX_WORKFLOW_NAME = 200;

export type WorkflowResultStatus = 'GREEN' | 'YELLOW' | 'RED';
export type WorkflowResultTrend = 'UP' | 'DOWN' | 'FLAT';
export type WorkflowResultState = 'VALID' | 'INVALID' | 'MISSING';

export interface WorkflowResultKpi { label: string; value: string; trend?: WorkflowResultTrend }

export interface WorkflowResult {
  title?: string;
  status: WorkflowResultStatus;
  kpis: WorkflowResultKpi[];
  items: string[];
  summary?: string;
}

export interface WorkflowRunSummary {
  runId: string;
  status: RunStatus;
  startedAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
  gateStatus: WorkflowResultStatus | null;
}

export interface WorkflowWidgetEntry {
  workflowId: string;
  workflowName: string | null;
  /** False for a workflow that was deleted (soft delete) or is unknown. */
  available: boolean;
  run: WorkflowRunSummary | null;
  resultState: WorkflowResultState;
  result: WorkflowResult | null;
}

const WORKFLOW_ID = /^[a-z0-9][a-z0-9-]{0,79}$/;
const STATUSES: readonly WorkflowResultStatus[] = ['GREEN', 'YELLOW', 'RED'];
const TRENDS: readonly WorkflowResultTrend[] = ['UP', 'DOWN', 'FLAT'];
const RUN_STATUSES: readonly RunStatus[] = [
  'PENDING', 'RUNNING', 'PAUSED', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED', 'INTERRUPTED',
];
const RESULT_FIELDS = new Set(['title', 'status', 'kpis', 'items', 'summary']);
const KPI_FIELDS = new Set(['label', 'value', 'trend']);
// Control, format, line/paragraph separator, surrogate, private use and space separator (checked
// per code point below, with an exception for the ordinary U+0020 — see isInvisible).
const INVISIBLE_CATEGORY = /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}\p{Cs}\p{Co}\p{Zs}]/u;
/**
 * Filler code points that are individually legal Unicode (not control/format/separator) but render
 * as blank: HANGUL FILLER and its choseong/jungseong siblings, HALFWIDTH HANGUL FILLER, and BRAILLE
 * PATTERN BLANK. Mirrors DashboardWidgetResultParser.INVISIBLE_FILLERS exactly.
 */
const INVISIBLE_FILLERS = new Set([0x2800, 0x3164, 0x115f, 0x1160, 0xffa0]);

/**
 * Mirrors DashboardWidgetResultParser#isInvisible exactly: any control, format, line/paragraph
 * separator, surrogate, private-use or space-separator code point (except the ordinary U+0020), plus
 * the filler denylist above — checked anywhere in the text, not only when the whole string is blank.
 */
function hasInvisible(text: string): boolean {
  for (const char of text) {
    const codePoint = char.codePointAt(0) ?? 0;
    if (codePoint === 0x20) continue;
    if (INVISIBLE_FILLERS.has(codePoint) || INVISIBLE_CATEGORY.test(char)) return true;
  }
  return false;
}

export function isWorkflowId(value: unknown): value is string {
  return typeof value === 'string' && WORKFLOW_ID.test(value);
}

/** The workflow ids of the shown workflow widgets: valid, deduplicated, sorted, capped. */
export function workflowIdsOf(widgets: readonly ResolvedWidget[]): string[] {
  const ids = new Set<string>();
  for (const widget of widgets) {
    if (widget.def.id !== WORKFLOW_STATUS_WIDGET_ID && widget.def.id !== WORKFLOW_RESULT_WIDGET_ID) continue;
    const workflowId = widget.instance.config?.workflowId;
    if (isWorkflowId(workflowId)) ids.add(workflowId);
  }
  return [...ids].sort().slice(0, MAX_WORKFLOW_WIDGET_IDS);
}

/** A contract string, or null when it is missing, not a string, empty, too long or not plainly visible. */
function cleanText(value: unknown, max: number): string | null {
  if (typeof value !== 'string') return null;
  const text = value.trim();
  if (text.length === 0 || text.length > max || hasInvisible(text)) return null;
  return text;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function kpisOf(value: unknown): WorkflowResultKpi[] | null {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length > WORKFLOW_RESULT_CAPS.kpis) return null;
  const kpis: WorkflowResultKpi[] = [];
  for (const entry of value) {
    if (!isObject(entry) || Object.keys(entry).some((key) => !KPI_FIELDS.has(key))) return null;
    const label = cleanText(entry.label, WORKFLOW_RESULT_CAPS.kpiText);
    const value_ = cleanText(entry.value, WORKFLOW_RESULT_CAPS.kpiText);
    if (!label || !value_) return null;
    const kpi: WorkflowResultKpi = { label, value: value_ };
    if (entry.trend !== undefined && entry.trend !== null) {
      const trend = TRENDS.find((known) => known === entry.trend);
      if (!trend) return null;
      kpi.trend = trend;
    }
    kpis.push(kpi);
  }
  return kpis;
}

function itemsOf(value: unknown): string[] | null {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length > WORKFLOW_RESULT_CAPS.items) return null;
  const items: string[] = [];
  for (const entry of value) {
    const item = cleanText(entry, WORKFLOW_RESULT_CAPS.item);
    if (!item) return null;
    items.push(item);
  }
  return items;
}

/** The validated contract, or null — there is no partial result, and no "repaired" one. */
export function parseWorkflowResult(value: unknown): WorkflowResult | null {
  if (!isObject(value)) return null;
  if (Object.keys(value).some((key) => !RESULT_FIELDS.has(key))) return null;
  const status = STATUSES.find((known) => known === value.status);
  if (!status) return null;
  const kpis = kpisOf(value.kpis);
  const items = itemsOf(value.items);
  if (!kpis || !items) return null;
  const result: WorkflowResult = { status, kpis, items };
  for (const [field, max] of [['title', WORKFLOW_RESULT_CAPS.title], ['summary', WORKFLOW_RESULT_CAPS.summary]] as const) {
    if (value[field] === undefined || value[field] === null) continue;
    const text = cleanText(value[field], max);
    if (!text) return null;
    result[field] = text;
  }
  return result;
}

function runOf(value: unknown): WorkflowRunSummary | null {
  if (!isObject(value)) return null;
  const status = RUN_STATUSES.find((known) => known === value.status);
  if (!isWorkflowId(value.runId) || !status) return null;
  return {
    runId: value.runId,
    status,
    startedAt: typeof value.startedAt === 'string' ? value.startedAt : null,
    completedAt: typeof value.completedAt === 'string' ? value.completedAt : null,
    durationMs: typeof value.durationMs === 'number' && Number.isFinite(value.durationMs) ? value.durationMs : null,
    gateStatus: STATUSES.find((known) => known === value.gateStatus) ?? null,
  };
}

/**
 * One entry of the endpoint's answer. The backend validated all of this already; checking it again
 * costs nothing and keeps a manipulated or outdated answer from reaching a widget. A result that
 * claims to be VALID but does not match the contract is downgraded to INVALID — never rendered.
 */
export function parseWorkflowEntry(value: unknown): WorkflowWidgetEntry | null {
  if (!isObject(value) || !isWorkflowId(value.workflowId)) return null;
  const available = value.available === true;
  const result = value.resultState === 'VALID' ? parseWorkflowResult(value.result) : null;
  const resultState: WorkflowResultState = value.resultState === 'VALID'
    ? (result ? 'VALID' : 'INVALID')
    : value.resultState === 'INVALID' ? 'INVALID' : 'MISSING';
  return {
    workflowId: value.workflowId,
    // Rendered as the widget heading, so it is cleaned the same way as a contract string (control,
    // format, zero-width, bidi overrides, surrogates, private use, blank-only) — a name that becomes
    // empty after that cleaning falls back to null, same as a name that was never sent.
    workflowName: available ? cleanText(value.workflowName, MAX_WORKFLOW_NAME) : null,
    available,
    run: available ? runOf(value.run) : null,
    resultState: available ? resultState : 'MISSING',
    result: available ? result : null,
  };
}
