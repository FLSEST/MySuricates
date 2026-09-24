import { countHits, normalize } from './query';
import { cronRunsOnDay } from '../cronSchedule';
import { resolveTemplateName } from '../templateUtils';
import type { ParsedQuery } from './types';
import type { PendingInput } from '../../hooks/usePendingInputs';
import type {
  PoolWorkflowEntry, Project, ScheduledWorkflow, WorkflowRunListItem, WorkflowTemplate,
} from '../../types';

/**
 * The command field's matchers per source — pure functions over data the hooks already have.
 * Every matcher returns its hits best first and leaves the trimming to `arrangeGroups`.
 */

const FINISHED_OK = new Set(['COMPLETED', 'COMPLETED_WITH_ERRORS']);

/** Weighted token hits: each field is `[text, weight]`. */
export function scoreFields(tokens: readonly string[], fields: ReadonlyArray<readonly [string | null | undefined, number]>): number {
  return fields.reduce((sum, [text, weight]) => sum + countHits(tokens, text) * weight, 0);
}

function byScore<T>(scored: Array<{ entry: T; score: number }>): T[] {
  return scored.filter((s) => s.score > 0).sort((a, b) => b.score - a.score).map((s) => s.entry);
}

export type CatalogEntry =
  | { source: 'template'; entry: WorkflowTemplate }
  | { source: 'pool'; entry: PoolWorkflowEntry };

/**
 * Templates (without blueprints — those need the editor) and pool entries whose name, description,
 * category or tags in the UI language contain the search words. Name hits weigh most.
 */
export function matchCatalog(
  tokens: readonly string[], templates: readonly WorkflowTemplate[], pool: readonly PoolWorkflowEntry[], locale: string,
): CatalogEntry[] {
  if (tokens.length === 0) return [];
  const scored: Array<{ entry: CatalogEntry; score: number }> = [];
  for (const tmpl of templates) {
    if (tmpl.kind === 'BLUEPRINT') continue;
    scored.push({
      entry: { source: 'template', entry: tmpl },
      score: scoreFields(tokens, [
        [resolveTemplateName(tmpl.name, locale), 3],
        [resolveTemplateName(tmpl.description, locale), 1],
        [tmpl.category, 1],
      ]),
    });
  }
  for (const entry of pool) {
    scored.push({
      entry: { source: 'pool', entry },
      score: scoreFields(tokens, [
        [resolveTemplateName(entry.name, locale), 3],
        [resolveTemplateName(entry.description, locale), 1],
        [entry.category, 1],
        [(entry.tags ?? []).join(' '), 1],
      ]),
    });
  }
  return byScore(scored);
}

/** Projects by name (weighs most) and description. */
export function matchProjects(tokens: readonly string[], projects: readonly Project[]): Project[] {
  if (tokens.length === 0) return [];
  return byScore(projects.map((p) => ({
    entry: p,
    score: scoreFields(tokens, [[p.name, 3], [p.description, 1]]),
  })));
}

/** Newest finished run (completed, also with errors) per workflow — the "latest result". */
export function latestResultRuns(runs: readonly WorkflowRunListItem[]): Map<string, WorkflowRunListItem> {
  const latest = new Map<string, WorkflowRunListItem>();
  for (const run of runs) {
    if (!FINISHED_OK.has(run.status)) continue;
    const prev = latest.get(run.workflowId);
    if (!prev || run.startedAt > prev.startedAt) latest.set(run.workflowId, run);
  }
  return latest;
}

function sameDay(iso: string | null | undefined, day: Date): boolean {
  if (!iso) return false;
  const d = new Date(iso);
  return d.getFullYear() === day.getFullYear() && d.getMonth() === day.getMonth() && d.getDate() === day.getDate();
}

function addDays(now: Date, days: number): Date {
  const d = new Date(now);
  d.setDate(d.getDate() + days);
  return d;
}

/**
 * Runs for the field. With a failure intent: failed runs, narrowed to the content words (workflow
 * name, error) and to today when "heute/today" was said. Otherwise the newest run of every workflow
 * whose name contains a search word. Newest first.
 */
export function pickRuns(query: ParsedQuery, runs: readonly WorkflowRunListItem[], now: Date): WorkflowRunListItem[] {
  const newestFirst = [...runs].sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? ''));
  if (query.intents.includes('failed')) {
    return newestFirst.filter((r) => r.status === 'FAILED'
      && (query.contentTokens.length === 0 || scoreFields(query.contentTokens, [[r.workflowName, 1], [r.error?.message, 1]]) > 0)
      && (query.dayOffset !== 0 || sameDay(r.startedAt, now)));
  }
  if (query.tokens.length === 0) return [];
  const seen = new Set<string>();
  const hits: WorkflowRunListItem[] = [];
  for (const run of newestFirst) {
    if (seen.has(run.workflowId) || countHits(query.tokens, run.workflowName) === 0) continue;
    seen.add(run.workflowId);
    hits.push(run);
  }
  return hits;
}

/**
 * What waits for an answer: everything on a waiting intent ("warte", "freigabe", "approval" …),
 * otherwise the entries whose workflow name or question contain a content word. Oldest first, as
 * the backend sends them.
 */
export function pickApprovals(query: ParsedQuery, pending: readonly PendingInput[]): PendingInput[] {
  if (query.intents.includes('waiting')) return [...pending];
  if (query.contentTokens.length === 0) return [];
  return pending.filter((p) => scoreFields(query.contentTokens, [[p.workflowName, 1], [p.question, 1]]) > 0);
}

/**
 * Schedules. A named day (heute, morgen, Freitag …) keeps the enabled ones that fire on that day —
 * by the cron expression, else by the next run; a schedule word without a day keeps all enabled ones;
 * content words narrow either by workflow name. Without a schedule intent only name matches count.
 * Sorted by next run.
 */
export function pickSchedules(query: ParsedQuery, schedules: readonly ScheduledWorkflow[], now: Date): ScheduledWorkflow[] {
  const intent = query.intents.includes('schedule');
  const nameOf = (s: ScheduledWorkflow) => s.workflowName ?? s.chainName ?? '';
  let hits: ScheduledWorkflow[];
  if (intent) {
    hits = schedules.filter((s) => s.enabled);
    if (query.dayOffset !== null) {
      const day = addDays(now, query.dayOffset);
      hits = hits.filter((s) => {
        const byCron = s.scheduleType === 'CRON' ? cronRunsOnDay(s.cronExpression, day) : null;
        return byCron ?? sameDay(s.nextRunAt, day);
      });
    }
    if (query.contentTokens.length > 0) {
      hits = hits.filter((s) => scoreFields(query.contentTokens, [[nameOf(s), 1], [s.description, 1]]) > 0);
    }
  } else {
    if (query.tokens.length === 0) return [];
    hits = schedules.filter((s) => countHits(query.tokens, nameOf(s)) > 0);
  }
  return [...hits].sort((a, b) => (a.nextRunAt ?? '￿').localeCompare(b.nextRunAt ?? '￿'));
}

/** Normalized equality of two labels — to recognize the query itself among the hits. */
export function sameText(a: string, b: string): boolean {
  return normalize(a.trim()) === normalize(b.trim());
}
