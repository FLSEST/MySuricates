import { useMemo } from 'react';
import { useT, useI18nStore } from '../../../i18n';
import { useWorkflows, useAllRuns } from '../../../hooks/useWorkflows';
import { useTemplates } from '../../../hooks/useTemplates';
import { usePoolWorkflows } from '../../../hooks/usePool';
import { usePendingInputs, isRejectOption } from '../../../hooks/usePendingInputs';
import { useTodos } from '../../../hooks/useTodos';
import { useProjects } from '../../../hooks/useProjects';
import { useSchedules } from '../../../hooks/useSchedules';
import { matchWorkflows } from '../../../utils/matchWorkflows';
import { latestRunStatusMap } from '../../../utils/latestRunStatus';
import { describeSchedule } from '../../../utils/cronSchedule';
import { formatRelativeTime, formatTimeUntil } from '../../../utils/formatTime';
import { resolveTemplateName } from '../../../utils/templateUtils';
import {
  latestResultRuns, matchCatalog, matchProjects, pickApprovals, pickRuns, pickSchedules,
} from '../../../utils/homeCommand/match';
import { serverSearchTerm } from '../../../utils/homeCommand/query';
import type { HomeItem, HomeSource, ParsedQuery } from '../../../utils/homeCommand/types';
import type { PendingInput } from '../../../hooks/usePendingInputs';

/**
 * What every source gets: the parsed query, whether requests beyond the home's own data may run
 * (the debounced query is non-empty) and "now" for relative days.
 */
export interface SourceInput {
  query: ParsedQuery;
  /** Debounced query is non-empty — only then do sources fetch data the home does not load anyway. */
  fetch: boolean;
  /** The debounced query, parsed — what server-side searches use. */
  settled: ParsedQuery;
  now: Date;
}

/** Same query key as SimpleOverview and the run chips — no extra request. */
const RECENT_RUNS = { size: 100 };

const runPage = (workflowId: string | null, runId: string) =>
  (workflowId ? `/workflows/${workflowId}/run/${runId}` : `/runs/${runId}`);

/** Plain first line of a Markdown question, for a one-line sub text. */
function firstLine(markdown: string): string {
  const line = markdown.split('\n').map((l) => l.trim()).find((l) => l.length > 0) ?? '';
  return line.replace(/[#*_`>]/g, '').trim();
}

/** Own workflows (start right away; the latest result when there is one). */
export function useWorkflowSource({ query }: SourceInput): HomeSource {
  const t = useT();
  const { data: workflows, isLoading } = useWorkflows();
  const { data: runsPage } = useAllRuns(RECENT_RUNS);
  return useMemo(() => {
    const c = t.simple.home.command;
    const runs = runsPage?.content ?? [];
    const results = latestResultRuns(runs);
    const status = latestRunStatusMap(runs);
    const items: HomeItem[] = matchWorkflows(query.tokens.join(' '), workflows ?? []).map(({ workflow: w }) => {
      const result = results.get(w.id);
      return {
        id: `workflow:${w.id}`,
        icon: w.icon ? { emoji: w.icon } : 'workflow',
        label: w.name,
        sub: w.description || undefined,
        status: status.get(w.id),
        primary: { label: c.start, action: { kind: 'run-workflow', workflow: w } },
        secondary: result
          ? [{ id: 'result', label: c.latestResult, action: { kind: 'navigate', to: runPage(w.id, result.id) } }]
          : undefined,
      };
    });
    return { id: 'workflows', items, loading: isLoading, showAllTo: '/workflows' };
  }, [t, query, workflows, runsPage, isLoading]);
}

/** Templates and pool entries — "new from a template" opens the Simple create flow with it chosen. */
export function useTemplateSource({ query, fetch }: SourceInput): HomeSource {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const templates = useTemplates({ enabled: fetch });
  const pool = usePoolWorkflows({ enabled: fetch });
  return useMemo(() => {
    const c = t.simple.home.command;
    const items: HomeItem[] = matchCatalog(query.tokens, templates.data ?? [], pool.data ?? [], locale).map((hit) => {
      const { entry } = hit;
      const param = hit.source === 'template' ? 'template' : 'pool';
      return {
        id: `${param}:${entry.id}`,
        icon: entry.icon ? { emoji: entry.icon } : hit.source === 'template' ? 'template' : 'pool',
        label: resolveTemplateName(entry.name, locale),
        sub: [hit.source === 'template' ? c.template : c.pool, resolveTemplateName(entry.description, locale)]
          .filter(Boolean).join(' · '),
        primary: { label: c.create, action: { kind: 'navigate', to: `/workflows?${param}=${encodeURIComponent(entry.id)}` } },
      };
    });
    return {
      id: 'templates', items, loading: templates.isLoading || pool.isLoading, showAllTo: '/workflows?new=1',
    };
  }, [t, query, templates.data, pool.data, templates.isLoading, pool.isLoading, locale]);
}

/** Recent runs by workflow name, failed ones on a failure intent — "try again" keeps the input. */
export function useRunSource({ query, now }: SourceInput): HomeSource {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: runsPage, isLoading } = useAllRuns(RECENT_RUNS);
  return useMemo(() => {
    const c = t.simple.home.command;
    const items: HomeItem[] = pickRuns(query, runsPage?.content ?? [], now).map((run) => {
      const failed = run.status === 'FAILED';
      const when = run.startedAt ? formatRelativeTime(run.startedAt, locale) : '';
      const sub = failed
        ? [c.failedAt.replace('{time}', when), run.error?.message].filter(Boolean).join(' · ')
        : c.ranAt.replace('{time}', when);
      return {
        id: `run:${run.id}`,
        icon: failed ? 'run-failed' : 'run',
        label: run.workflowName,
        sub,
        status: run.status,
        primary: { label: c.open, action: { kind: 'navigate', to: runPage(run.workflowId, run.id) } },
        secondary: failed
          ? [{ id: 'retry', label: t.simple.runs.retry, action: { kind: 'retry-run', runId: run.id } }]
          : undefined,
      };
    });
    return { id: 'runs', items, loading: isLoading, showAllTo: '/runs' };
  }, [t, locale, query, runsPage, isLoading, now]);
}

function approvalItem(p: PendingInput, t: ReturnType<typeof useT>, locale: 'de' | 'en'): HomeItem {
  const c = t.simple.home.command;
  const approval = p.type === 'approval';
  const question = firstLine(p.question);
  const asked = `${t.approvals.asked} ${formatRelativeTime(p.requestedAt, locale)}`;
  return {
    id: `approval:${p.runId}:${p.blockId}`,
    icon: approval ? 'approval' : 'input',
    label: p.workflowName ?? t.approvals.deletedWorkflow,
    sub: [approval ? question : c.humanInput, asked].filter(Boolean).join(' · '),
    human: true,
    primary: { label: c.open, action: { kind: 'navigate', to: runPage(p.workflowId, p.runId) } },
    secondary: approval
      ? p.options.map((option) => ({
        id: `answer:${option}`,
        label: option,
        tone: isRejectOption(option, p.rejectOptions) ? 'danger' : 'human',
        action: { kind: 'answer-approval', runId: p.runId, answer: option },
      }))
      : undefined,
  };
}

/** Runs waiting for an answer — approvals are answered right in the row, human input on the run page. */
export function useApprovalSource({ query }: SourceInput): HomeSource {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  // Polled by SimpleOverview anyway — same query, no extra request
  const { data: pending, isLoading } = usePendingInputs();
  return useMemo(() => ({
    id: 'approvals',
    items: pickApprovals(query, pending ?? []).map((p) => approvalItem(p, t, locale)),
    loading: isLoading,
    showAllTo: '/approvals',
  }), [t, locale, query, pending, isLoading]);
}

const OPEN_STATUSES = ['OPEN', 'IN_PROGRESS'];
const TODO_HITS = 3;

/** Open tasks (server search) and "add as task: …" — first with the "aufgabe:" prefix, else last. */
export function useTodoSource({ query, fetch, settled }: SourceInput): HomeSource {
  const t = useT();
  const term = settled.todoText === null ? serverSearchTerm(settled) : null;
  const search = useTodos({ q: term ?? '', status: OPEN_STATUSES }, 0, TODO_HITS, fetch && term !== null);
  return useMemo(() => {
    const c = t.simple.home.command;
    const create = (title: string, fallback: boolean): HomeItem => ({
      id: 'todo:create',
      icon: 'todo-add',
      label: c.addTodo.replace('{text}', title),
      fallback,
      primary: { label: c.add, action: { kind: 'create-todo', title } },
    });
    if (query.todoText !== null) {
      return { id: 'todos', items: query.todoText ? [create(query.todoText, false)] : [], loading: false, showAllTo: '/todos' };
    }
    const found: HomeItem[] = term === null ? [] : (search.data?.content ?? []).map((todo) => ({
      id: `todo:${todo.id}`,
      icon: 'todo',
      label: todo.title,
      sub: todo.projectName ?? undefined,
      primary: { label: c.open, action: { kind: 'navigate', to: '/todos' } },
    }));
    const items = query.raw.length >= 3 ? [...found, create(query.raw, true)] : found;
    return {
      id: 'todos',
      items,
      loading: search.isLoading,
      showAllTo: '/todos',
      more: (search.data?.totalElements ?? 0) > TODO_HITS,
    };
  }, [t, query, term, search.data, search.isLoading]);
}

/** Projects by name — the project page is Pro, so the row carries the Pro badge. */
export function useProjectSource({ query }: SourceInput): HomeSource {
  const t = useT();
  const { data: projects, isLoading } = useProjects();
  return useMemo(() => {
    const c = t.simple.home.command;
    const items: HomeItem[] = matchProjects(query.tokens, projects ?? []).map((p) => ({
      id: `project:${p.id}`,
      icon: 'project',
      label: p.name,
      sub: c.projectWorkflows.replace('{n}', String(p.workflowCount)),
      pro: true,
      primary: { label: c.open, action: { kind: 'navigate', to: `/projects/${p.id}` } },
    }));
    return { id: 'projects', items, loading: isLoading, showAllTo: '/projects' };
  }, [t, query, projects, isLoading]);
}

/**
 * Schedules on a time word ("heute", "Freitag", "zeitplan" …) or by workflow name; on a schedule word
 * with a matching workflow also "schedule <workflow>", which opens the create dialog with it chosen.
 */
export function useScheduleSource({ query, fetch, now }: SourceInput): HomeSource {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: schedules, isLoading } = useSchedules({ enabled: fetch });
  const { data: workflows } = useWorkflows();
  return useMemo(() => {
    const c = t.simple.home.command;
    const hits = pickSchedules(query, schedules ?? [], now);
    const items: HomeItem[] = hits.map((s) => {
      const when = describeSchedule(s, t);
      const next = s.nextRunAt ? c.nextRun.replace('{time}', formatTimeUntil(s.nextRunAt, locale)) : null;
      return {
        id: `schedule:${s.id}`,
        icon: 'schedule',
        label: s.workflowName ?? s.chainName ?? '—',
        sub: [when ?? t.simple.schedules.customTiming, next].filter(Boolean).join(' · '),
        status: s.lastRunStatus ?? undefined,
        pro: when === null,
        primary: { label: c.open, action: { kind: 'navigate', to: '/schedules' } },
      };
    });
    const target = query.intents.includes('schedule') && query.contentTokens.length > 0
      ? matchWorkflows(query.contentTokens.join(' '), workflows ?? [])[0]?.workflow
      : undefined;
    // Offered first — it is what "zeitplan <name>" asks for — unless that workflow already has one listed
    if (target && !hits.some((s) => s.workflowId === target.id)) {
      items.unshift({
        id: `schedule:create:${target.id}`,
        icon: 'schedule-add',
        label: c.scheduleFor.replace('{name}', target.name),
        primary: {
          label: c.create,
          action: { kind: 'navigate', to: `/schedules?new=1&workflow=${encodeURIComponent(target.id)}` },
        },
      });
    }
    return { id: 'schedules', items, loading: isLoading, showAllTo: '/schedules' };
  }, [t, locale, query, schedules, isLoading, workflows, now]);
}
