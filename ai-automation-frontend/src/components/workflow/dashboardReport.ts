/**
 * The dashboard-report block's config as an editable form — and back again.
 *
 * Every limit and the list of statuses come from the ONE frontend contract mirror
 * (`components/dashboard/workflowWidgetContract.ts`, itself the mirror of the backend's
 * DashboardWidgetResultParser). Nothing about the contract is decided here: this module only maps
 * between the JSON a workflow stores and the fields a human fills in, and it warns about what the
 * backend would refuse. The backend stays the authority — the editor is not a gate.
 */

import { WORKFLOW_RESULT_CAPS, type WorkflowResult } from '../dashboard/workflowWidgetContract';

export const REPORT_STATUSES = ['GREEN', 'YELLOW', 'RED'] as const;
export type ReportStatus = (typeof REPORT_STATUSES)[number];
export type ReportTrend = '' | 'UP' | 'DOWN' | 'FLAT';

export interface ReportKpiForm {
  label: string;
  value: string;
  trend: ReportTrend;
}

export interface ReportForm {
  status: string;
  title: string;
  summary: string;
  kpis: ReportKpiForm[];
  items: string[];
}

export const EMPTY_REPORT_FORM: ReportForm = {
  status: '', title: '', summary: '', kpis: [], items: [],
};

/** The five keys this form owns. Every other config key is left untouched. */
const CONTRACT_KEYS = ['status', 'title', 'summary', 'kpis', 'items'] as const;

const TRENDS: readonly ReportTrend[] = ['UP', 'DOWN', 'FLAT'];

/** Same shape the backend's TemplateResolver looks for. */
const PLACEHOLDER = /\{\{\s*[^}]*\}\}/;

export function hasPlaceholder(value: string): boolean {
  return PLACEHOLDER.test(value);
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function kpiFrom(value: unknown): ReportKpiForm | null {
  if (!isObject(value)) return null;
  const trend = TRENDS.find((known) => known === value.trend) ?? '';
  return { label: text(value.label), value: text(value.value), trend };
}

/** The form behind a config JSON string, or null when the string is not a JSON object. */
export function formFromConfig(configJson: string): ReportForm | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(configJson);
  } catch {
    return null;
  }
  if (!isObject(parsed)) return null;
  const kpis = Array.isArray(parsed.kpis)
    ? parsed.kpis.map(kpiFrom).filter((kpi): kpi is ReportKpiForm => kpi !== null)
    : [];
  const items = Array.isArray(parsed.items)
    ? parsed.items.filter((item): item is string => typeof item === 'string')
    : [];
  return { status: text(parsed.status), title: text(parsed.title), summary: text(parsed.summary), kpis, items };
}

/**
 * The config JSON with the five contract keys rewritten from the form. Everything the form does not
 * own (timeout_seconds, leftovers of another block type) survives untouched.
 *
 * An empty scalar field is removed rather than written as an empty string. Rows are different:
 * a KPI or item row stays even while it is still blank. The form is rebuilt from the config on
 * every keystroke, so a row that got filtered out here would disappear the instant it was added —
 * before anyone could type in it. A blank row costs nothing: the backend drops it at run time
 * (DashboardReportCandidate), so it never reaches the dashboard. Emptying a list removes the key.
 */
export function configJsonWithReport(configJson: string, form: ReportForm): string {
  let base: Record<string, unknown> = {};
  try {
    const parsed: unknown = JSON.parse(configJson);
    if (isObject(parsed)) base = { ...parsed };
  } catch {
    base = {};
  }
  for (const key of CONTRACT_KEYS) delete base[key];

  const put = (key: 'status' | 'title' | 'summary') => {
    const value = form[key].trim();
    if (value) base[key] = value;
  };
  put('status');
  put('title');
  put('summary');

  if (form.kpis.length > 0) {
    base.kpis = form.kpis.map((kpi) => {
      const label = kpi.label.trim();
      const value = kpi.value.trim();
      return kpi.trend ? { label, value, trend: kpi.trend } : { label, value };
    });
  }
  if (form.items.length > 0) {
    base.items = form.items.map((item) => item.trim());
  }

  return JSON.stringify(base, null, 2);
}

/** A backend-validated proposal as form fields. Proposals never contain placeholders. */
export function formFromProposal(proposal: WorkflowResult): ReportForm {
  return {
    status: proposal.status,
    title: proposal.title ?? '',
    summary: proposal.summary ?? '',
    kpis: proposal.kpis.map((kpi) => ({ label: kpi.label, value: kpi.value, trend: kpi.trend ?? '' })),
    items: [...proposal.items],
  };
}

export type ReportWarningRule =
  'statusRequired' | 'statusUnknown' | 'tooLong' | 'tooMany' | 'kpiIncomplete';

export interface ReportWarning {
  /** Field path as the backend names it: 'title', 'items', 'kpis[2].label'. */
  field: string;
  rule: ReportWarningRule;
}

/**
 * What the backend would refuse, as far as the editor can tell. A value containing a placeholder is
 * skipped for the length checks: its real value only exists at run time, so judging it here would
 * warn about the wrong string.
 */
export function reportWarnings(form: ReportForm): ReportWarning[] {
  const warnings: ReportWarning[] = [];
  const status = form.status.trim();
  if (status === '') {
    warnings.push({ field: 'status', rule: 'statusRequired' });
  } else if (!hasPlaceholder(status) && !REPORT_STATUSES.some((known) => known === status)) {
    warnings.push({ field: 'status', rule: 'statusUnknown' });
  }
  const checkLength = (field: string, value: string, max: number) => {
    const trimmed = value.trim();
    if (trimmed !== '' && !hasPlaceholder(trimmed) && trimmed.length > max) {
      warnings.push({ field, rule: 'tooLong' });
    }
  };
  checkLength('title', form.title, WORKFLOW_RESULT_CAPS.title);
  checkLength('summary', form.summary, WORKFLOW_RESULT_CAPS.summary);

  if (form.kpis.length > WORKFLOW_RESULT_CAPS.kpis) warnings.push({ field: 'kpis', rule: 'tooMany' });
  form.kpis.forEach((kpi, index) => {
    const at = `kpis[${index + 1}]`;
    const label = kpi.label.trim();
    const value = kpi.value.trim();
    // A row nobody has touched yet is dropped at run time and stays silent. Once any of the three
    // fields carries something — a trend alone counts — label and value are both required, and the
    // backend says so ('kpis[n].label is required') rather than dropping the row.
    const started = label !== '' || value !== '' || kpi.trend !== '';
    if (started && (label === '' || value === '')) warnings.push({ field: at, rule: 'kpiIncomplete' });
    checkLength(`${at}.label`, kpi.label, WORKFLOW_RESULT_CAPS.kpiText);
    checkLength(`${at}.value`, kpi.value, WORKFLOW_RESULT_CAPS.kpiText);
  });

  if (form.items.length > WORKFLOW_RESULT_CAPS.items) warnings.push({ field: 'items', rule: 'tooMany' });
  form.items.forEach((item, index) => checkLength(`items[${index + 1}]`, item, WORKFLOW_RESULT_CAPS.item));

  return warnings;
}
