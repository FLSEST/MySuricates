import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, CalendarDays, ChartGantt, List, Network, Target, Trash2 } from 'lucide-react';
import { Button, Card, ConfirmModal, Select, Spinner } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { TodoProgressBar } from '../components/todo/TodoProgressBar';
import { GoalTodoList } from '../components/goal/GoalTodoList';
import { GoalMapOutdatedBackend, GoalRelationMap } from '../components/goal/GoalRelationMap';
import { GoalCandidates, type CandidateFilter } from '../components/goal/GoalCandidates';
import { GoalTodoEditor } from '../components/goal/GoalTodoEditor';
import { GoalTimeline } from '../components/goal/GoalTimeline';
import { GoalGuardControl } from '../components/goal/GoalGuardControl';
import { planBadge } from '../components/goal/planDisplay';
import { dayOf } from '../components/goal/timelineLayout';
import {
  useAssignGoalTodos, useDeleteGoal, useGoal, useGoalCandidates, useGoalRelations, useUpdateGoal,
} from '../hooks/useGoals';
import { useI18nStore, useT } from '../i18n';
import { formatDate } from '../utils/localeFormat';
import { apiErrorStatus } from '../api/client';
import type { GoalStatus, GoalTodoItem } from '../types';

const GOAL_STATUSES: GoalStatus[] = ['ACTIVE', 'DONE', 'ARCHIVED'];

type GoalView = 'list' | 'map' | 'timeline';
const VIEW_KEY = 'mysuricates-goal-view';
const VIEWS: readonly GoalView[] = ['list', 'map', 'timeline'];
/** Immer als Tag-Vorschlag dabei: Ziele sind oft Releases. */
const DEFAULT_TAG_SUGGESTION = 'release';

function readView(): GoalView {
  try {
    const stored = localStorage.getItem(VIEW_KEY);
    return VIEWS.find((view) => view === stored) ?? 'list';
  } catch {
    return 'list';
  }
}

function storeView(view: GoalView) {
  try {
    localStorage.setItem(VIEW_KEY, view);
  } catch {
    // privates Fenster oder gesperrter Speicher: die Wahl gilt dann nur bis zum Neuladen
  }
}

/** `release` und die häufigsten Tags der TODOs des Ziels. */
function tagSuggestions(todos: GoalTodoItem[]): string[] {
  const counts = new Map<string, number>();
  todos.flatMap((todo) => todo.tags).forEach((tag) => counts.set(tag, (counts.get(tag) ?? 0) + 1));
  const frequent = [...counts.entries()].sort((a, b) => b[1] - a[1]).map(([tag]) => tag);
  return [...new Set([DEFAULT_TAG_SUGGESTION, ...frequent])].slice(0, 5);
}

/** Filter der Kandidaten, die Suche verzögert – nicht jeder Tastendruck ist eine Abfrage. */
function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), ms);
    return () => window.clearTimeout(timer);
  }, [value, ms]);
  return debounced;
}

/**
 * Ein Ziel mit seinen TODOs (TODO dc0566f8): als Liste – was als Nächstes drankommt, offene Fragen,
 * Erledigtes –, als Relationskarte, woher die TODOs kommen und welche Workflows sie abarbeiten
 * (TODO c6ea8f5c), oder als Zeitstrahl mit Fälligkeiten und Meilensteinen (TODO e192df18). Im Kopf
 * der Schalter für den Ziel-Wächter, darunter Vorschläge, die zum Ziel passen könnten.
 */
export function GoalDetail() {
  const { goalId } = useParams<{ goalId: string }>();
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const navigate = useNavigate();
  const [view, setView] = useState<GoalView>(readView);
  const [filter, setFilter] = useState<CandidateFilter>({ q: '', tag: '' });
  const [editing, setEditing] = useState<string | null>(null);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const appliedFilter = useDebounced(filter, 300);
  const { data, isLoading, error, refetch } = useGoal(goalId);
  const candidatesPage = useGoalCandidates(appliedFilter, !!data);
  const candidates = useMemo(() => candidatesPage.data?.content ?? [], [candidatesPage.data]);
  const relations = useGoalRelations(goalId, candidates.map((todo) => todo.id), view === 'map');
  const suggestions = useMemo(() => tagSuggestions(data?.todos ?? []), [data?.todos]);
  const [today] = useState(() => dayOf(new Date()));
  const badges = useMemo(() => {
    const milestones = new Map((data?.milestones ?? []).map((milestone) => [milestone.id, milestone]));
    const entries = (data?.todos ?? []).flatMap((todo) => {
      const badge = planBadge(todo, todo.milestoneId ? milestones.get(todo.milestoneId) : undefined, today, locale, t);
      return badge ? [[todo.id, badge] as const] : [];
    });
    return new Map(entries);
  }, [data?.todos, data?.milestones, today, locale, t]);
  const updateGoal = useUpdateGoal();
  const deleteGoal = useDeleteGoal();
  const assign = useAssignGoalTodos();

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }
  if (error || !data) {
    return <ErrorState message={error?.message ?? t.goals.notFound} onRetry={() => refetch()} />;
  }

  const { goal, todos, byProject, milestones } = data;
  const chooseView = (next: GoalView) => {
    setView(next);
    storeView(next);
  };
  const assignTodo = (todoId: string) => assign.mutate({ id: goal.id, todoIds: [todoId] });

  const remove = () => deleteGoal.mutate(goal.id, {
    onSuccess: () => navigate('/goals'),
    onSettled: () => setConfirmRemove(false),
  });

  return (
    <div className="space-y-6 animate-fade-in">
      <Link to="/goals" className="inline-flex items-center gap-1 text-sm text-text-secondary hover:text-text">
        <ArrowLeft className="w-3.5 h-3.5" />
        {t.goals.back}
      </Link>

      <div className="flex flex-wrap items-start gap-3">
        <div className="p-2 rounded-xl bg-accent/10 text-accent">
          <Target className="w-5 h-5" />
        </div>
        <div className="flex-1 min-w-0">
          <h1 className="text-2xl font-bold text-text">{goal.title}</h1>
          {goal.targetDate && (
            <p className="flex items-center gap-1 text-sm text-text-secondary mt-0.5">
              <CalendarDays className="w-3.5 h-3.5" />
              {t.goals.targetDate.replace('{date}', formatDate(goal.targetDate, locale))}
            </p>
          )}
          <div className="mt-2">
            <GoalGuardControl goal={goal} byProject={byProject} />
          </div>
        </div>
        <div className="w-40">
          <Select
            aria-label={t.common.status}
            value={goal.status}
            onChange={(e) => updateGoal.mutate({ id: goal.id, data: { status: e.target.value as GoalStatus } })}
            options={GOAL_STATUSES.map((s) => ({ value: s, label: t.goals.statuses[s] }))}
          />
        </div>
        <Button variant="secondary" size="sm" onClick={() => setConfirmRemove(true)} aria-label={t.goals.remove}>
          <Trash2 className="w-3.5 h-3.5" />
        </Button>
      </div>

      {goal.description && (
        <Card>
          <p className="text-sm text-text-secondary whitespace-pre-wrap">{goal.description}</p>
        </Card>
      )}

      <Card className="space-y-3">
        <TodoProgressBar open={goal.progress.open} inProgress={goal.progress.inProgress}
          done={goal.progress.done} backlog={goal.progress.backlog} />
        <p className="text-sm text-text-secondary" data-testid="goal-progress">
          {t.goals.progress.replace('{done}', String(goal.progress.done)).replace('{total}', String(goal.progress.total))}
        </p>
        {byProject.length > 1 && (
          <div className="text-xs text-text-secondary space-y-1">
            <p className="font-medium">{t.goals.byProject}</p>
            {byProject.map((p) => (
              <p key={p.projectId ?? 'none'}>
                {p.projectName ?? t.goals.noProject}: {p.done}/{p.total}
              </p>
            ))}
          </div>
        )}
      </Card>

      <div role="tablist" aria-label={t.goals.views.label} className="inline-flex gap-1 p-1 rounded-lg bg-surface-secondary">
        {([
          ['list', List, t.goals.views.list], ['map', Network, t.goals.views.map], ['timeline', ChartGantt, t.goals.views.timeline],
        ] as const).map(([key, Icon, label]) => (
          <button key={key} type="button" role="tab" aria-selected={view === key} onClick={() => chooseView(key)}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md transition-colors ${
              view === key ? 'bg-surface text-text shadow-sm font-medium' : 'text-text-secondary hover:text-text'}`}>
            <Icon className="w-3.5 h-3.5" />
            {label}
          </button>
        ))}
      </div>

      {view === 'list' ? (
        <GoalTodoList goalId={goal.id} todos={todos} milestones={milestones} today={today} />
      ) : view === 'timeline' ? (
        <GoalTimeline goal={goal} todos={todos} milestones={milestones} onOpenTodo={setEditing} />
      ) : relations.isLoading || !relations.data ? (
        relations.error
          ? apiErrorStatus(relations.error) === 404
            ? <GoalMapOutdatedBackend onShowList={() => chooseView('list')} />
            : <ErrorState message={relations.error.message} onRetry={() => relations.refetch()} />
          : <div className="flex justify-center py-8"><Spinner text={t.common.loading} /></div>
      ) : (
        <GoalRelationMap relations={relations.data} onOpenTodo={setEditing} onAssign={assignTodo} badges={badges} />
      )}

      <GoalCandidates
        filter={filter}
        onFilterChange={setFilter}
        candidates={candidates}
        isLoading={candidatesPage.isLoading}
        suggestions={suggestions}
        onAssign={assignTodo}
        assigning={assign.isPending}
      />

      {editing && <GoalTodoEditor todoId={editing} onClose={() => setEditing(null)} />}

      {/* 23635ef6: the app's own dialog instead of window.confirm */}
      <ConfirmModal
        open={confirmRemove}
        onClose={() => setConfirmRemove(false)}
        onConfirm={remove}
        title={t.goals.remove}
        message={t.goals.removeConfirm}
        confirmLabel={t.common.delete}
        confirmIcon={<Trash2 className="w-4 h-4" />}
        variant="danger"
        isPending={deleteGoal.isPending}
      />
    </div>
  );
}
