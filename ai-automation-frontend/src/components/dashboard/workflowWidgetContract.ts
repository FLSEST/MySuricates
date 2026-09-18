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
 * these values only as React text nodes. Two different rules apply on purpose here, mirroring
 * DashboardWidgetTextRule/DashboardWidgetResultParser#text exactly (fix round 2, B1+B2 — round 1's
 * "any space separator anywhere is fatal" was a different and stricter rule than the backend's own,
 * which broke ordinary German text such as "12 000", "z. B." or "§ 5"):
 * 1. Fatal anywhere in the string — rejects the string regardless of what else is there: control
 *    (Cc) and format (Cf, zero width, BOM, bidi overrides included) characters, line/paragraph
 *    separators, lone surrogates, private-use code points.
 * 2. Not visible, but merely excluded from counting — legal anywhere in the string; a string is
 *    valid only if at least one of its code points is neither in this class nor an ordinary U+0020
 *    space: every space separator other than U+0020 (so a non-breaking, figure or narrow no-break
 *    space no longer breaks a thousands separator or an abbreviation), the known invisible filler
 *    letters/symbols (U+2800, U+3164, U+115F, U+1160, U+FFA0), the non-format default-ignorable code
 *    points (U+034F, U+17B4-U+17B5, U+180B-U+180F, U+FE00-U+FE0F, U+E0100-U+E01EF — a lone variation
 *    selector or vowel-shaping mark without a base character), and unassigned code points.
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

/** Mirrors DashboardWidgetTextRule#isFatal exactly: fatal wherever it occurs in the string. */
const FATAL = /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}\p{Cs}\p{Co}]/u;

/**
 * Filler code points that are individually legal Unicode (not control/format/separator) but render
 * as blank: HANGUL FILLER and its choseong/jungseong siblings, HALFWIDTH HANGUL FILLER, and BRAILLE
 * PATTERN BLANK. Mirrors DashboardWidgetTextRule.INVISIBLE_FILLERS exactly.
 */
const INVISIBLE_FILLERS = new Set([0x2800, 0x3164, 0x115f, 0x1160, 0xffa0]);

/** Space separator (any) or unassigned — part of "not visible", checked per code point below. */
const NOT_VISIBLE_CATEGORY = /[\p{Zs}\p{Cn}]/u;

/**
 * Non-format default-ignorable code points: individually legal Unicode (mostly category Mn) that
 * render as nothing on their own — a bare combining grapheme joiner, a lone variation selector, or a
 * Mongolian/Khmer vowel-shaping mark without its base. Mirrors
 * DashboardWidgetTextRule#isNonFormatDefaultIgnorable exactly (named as explicit ranges because no
 * JS API exposes a direct Default_Ignorable_Code_Point lookup either).
 */
function isNonFormatDefaultIgnorable(codePoint: number): boolean {
  return codePoint === 0x034f
    || (codePoint >= 0x17b4 && codePoint <= 0x17b5)
    || (codePoint >= 0x180b && codePoint <= 0x180f)
    || (codePoint >= 0xfe00 && codePoint <= 0xfe0f)
    || (codePoint >= 0xe0100 && codePoint <= 0xe01ef);
}

/**
 * True for a code point that counts toward "the string has visible content". Mirrors
 * DashboardWidgetTextRule#isVisible exactly: a normal space (U+0020) is legal but does not itself
 * count — nor does every space separator other than U+0020, the invisible filler letters/symbols,
 * the non-format default-ignorable code points, or an unassigned code point.
 */
function isVisible(codePoint: number, char: string): boolean {
  if (codePoint === 0x20) return false;
  if (INVISIBLE_FILLERS.has(codePoint) || isNonFormatDefaultIgnorable(codePoint)) return false;
  return !NOT_VISIBLE_CATEGORY.test(char);
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

/**
 * A contract string: trimmed, non-empty, within its cap, free of fatal characters, and with at least
 * one visible code point remaining — mirrors DashboardWidgetResultParser#text exactly. Null for
 * anything that fails one of those checks; invisible-but-not-fatal characters elsewhere in an
 * otherwise visible string are kept as-is, not stripped.
 */
function cleanText(value: unknown, max: number): string | null {
  if (typeof value !== 'string') return null;
  const text = value.trim();
  if (text.length === 0 || text.length > max) return null;
  let sawVisible = false;
  for (const char of text) {
    if (FATAL.test(char)) return null;
    if (!sawVisible && isVisible(char.codePointAt(0) ?? 0, char)) sawVisible = true;
  }
  if (!sawVisible) return null;
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
