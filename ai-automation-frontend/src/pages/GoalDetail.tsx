import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, CalendarDays, ChartGantt, List, ListTree, Network, Target, Trash2 } from 'lucide-react';
import { Button, Card, ConfirmModal, Select, Spinner } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { TodoProgressBar } from '../components/todo/TodoProgressBar';
import { GoalTodoList } from '../components/goal/GoalTodoList';
import { GoalMapOutdatedBackend, GoalRelationMap } from '../components/goal/GoalRelationMap';
import { GoalCandidates, type CandidateFilter } from '../components/goal/GoalCandidates';
import { GoalTodoEditor } from '../components/goal/GoalTodoEditor';
import { GoalTimeline } from '../components/goal/GoalTimeline';
import { GoalGuardControl } from '../components/goal/GoalGuardControl';
import { GoalOutline } from '../components/goal/GoalOutline';
import { GoalProjectsBar } from '../components/goal/GoalProjectsBar';
import { DecisionCounts } from '../components/goal/DecisionCounts';
import { DecisionContext } from '../components/goal/decisionContext';
import { GoalWorkflowsProvider } from '../components/goal/GoalWorkflowsProvider';
import { GoalWorkflowsPanel } from '../components/goal/GoalWorkflowsPanel';
import { GoalSidePanel } from '../components/goal/GoalSidePanel';
import {
  GoalSelectionContext, SELECTION_PARAM, parseSelection, selectionValue, type GoalSelection, type GoalSelectionValue,
} from '../components/goal/goalSelection';
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

type GoalView = 'outline' | 'list' | 'map' | 'timeline';
/**
 * Neuer Schlüssel mit der Gliederung als Standard (c60bf914) – unter dem alten stünde bei allen, die
 * schon eine Ansicht gewählt hatten, weiter die alte, und die Gliederung bliebe unentdeckt.
 */
const VIEW_KEY = 'mysuricates-goal-view-v2';
const VIEWS: readonly GoalView[] = ['outline', 'list', 'map', 'timeline'];
/** Immer als Tag-Vorschlag dabei: Ziele sind oft Releases. */
const DEFAULT_TAG_SUGGESTION = 'release';

function readView(): GoalView {
  try {
    const stored = localStorage.getItem(VIEW_KEY);
    return VIEWS.find((view) => view === stored) ?? 'outline';
  } catch {
    return 'outline';
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
 * Ein Ziel mit seinen TODOs (TODO dc0566f8): gegliedert in Meilensteine, Projekte und Arbeitspakete
 * (Standard, c60bf914), als Liste – was als Nächstes drankommt, offene Fragen, Erledigtes –, als
 * Relationskarte, woher die TODOs kommen und welche Workflows sie abarbeiten (TODO c6ea8f5c), oder als
 * Zeitstrahl mit Meilensteinen, Arbeitspaketen als Balken und Fälligkeiten (TODO e192df18). Im Kopf
 * der Schalter für den Ziel-Wächter, darunter Vorschläge, die zum Ziel passen könnten.
 */
export function GoalDetail() {
  const { goalId } = useParams<{ goalId: string }>();
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const navigate = useNavigate();
  const [view, setView] = useState<GoalView>(readView);
  const [filter, setFilter] = useState<CandidateFilter>({ q: '', tag: '' });
  const [allProjects, setAllProjects] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const appliedFilter = useDebounced(filter, 300);
  const { data, isLoading, error, refetch } = useGoal(goalId);
  const candidatesPage = useGoalCandidates(goalId, appliedFilter, allProjects, !!data);
  const candidates = useMemo(() => candidatesPage.data?.content ?? [], [candidatesPage.data]);
  const relations = useGoalRelations(goalId, candidates.map((todo) => todo.id), view === 'map');
  const suggestions = useMemo(() => tagSuggestions(data?.todos ?? []), [data?.todos]);
  const [today] = useState(() => dayOf(new Date()));
  const badges = useMemo(() => {
    const packages = new Map((data?.workPackages ?? []).map((item) => [item.id, item]));
    const entries = (data?.todos ?? []).flatMap((todo) => {
      const badge = planBadge(todo, todo.workPackageId ? packages.get(todo.workPackageId) : undefined, today, locale, t);
      return badge ? [[todo.id, badge] as const] : [];
    });
    return new Map(entries);
  }, [data?.todos, data?.workPackages, today, locale, t]);
  const updateGoal = useUpdateGoal();
  const deleteGoal = useDeleteGoal();
  const assign = useAssignGoalTodos();
  const decisionScope = useMemo(() => (goalId ? { goalId, enabled: !!data?.decisionsSupported } : null), [goalId, data?.decisionsSupported]);
  // Seitenleiste (c60bf914): Auswahl in der URL, damit Zurück und Neuladen sie behalten
  const location = useLocation();
  const selectedParam = new URLSearchParams(location.search).get(SELECTION_PARAM);
  /** Neue Suche mit geänderter Auswahl – lesbar (`item=milestone:<id>`), ohne `%3A`. */
  const withSelection = useCallback((value: string | null) => {
    const params = new URLSearchParams(location.search);
    if (value) params.set(SELECTION_PARAM, value);
    else params.delete(SELECTION_PARAM);
    const search = params.toString().replace(/%3A/gi, ':');
    return search ? `?${search}` : '';
  }, [location.search]);
  const selected = useMemo(() => parseSelection(selectedParam), [selectedParam]);
  const selectionTrigger = useRef<HTMLElement | null>(null);
  const select = useCallback((next: GoalSelection, trigger?: HTMLElement | null) => {
    // Aus der Leiste heraus (verknüpftes Paket, Meilenstein) bleibt der ursprüngliche Auslöser das Ziel des Fokus
    if (trigger && !trigger.closest('[data-testid="goal-side-panel"]')) selectionTrigger.current = trigger;
    // Ersetzen statt anhängen (Review c60bf914): Jedes Öffnen, Wechseln und Schließen neu im Verlauf
    // hieße, sich mit Zurück durch die Auswahl zu klicken, statt die Seite zu verlassen
    navigate({ search: withSelection(selectionValue(next)) }, { replace: true });
  }, [navigate, withSelection]);
  const closeSelection = useCallback(() => {
    navigate({ search: withSelection(null) }, { replace: true });
    const trigger = selectionTrigger.current;
    selectionTrigger.current = null;
    if (trigger?.isConnected) trigger.focus();
  }, [navigate, withSelection]);
  const selection = useMemo<GoalSelectionValue>(() => ({ selected, select, close: closeSelection }), [selected, select, closeSelection]);

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

  const { goal, todos, byProject, workPackages, milestones, projects } = data;
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
    <GoalWorkflowsProvider detail={data}>
      <GoalSelectionContext.Provider value={selection}>
        <div className="lg:flex lg:items-start lg:gap-6">
          <div className="min-w-0 flex-1 space-y-6 animate-fade-in">
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
                <div className="mt-2 space-y-2">
                  <GoalGuardControl goal={goal} byProject={byProject} />
                  <GoalWorkflowsPanel />
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
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
                <p className="text-sm text-text-secondary" data-testid="goal-progress">
                  {t.goals.progress.replace('{done}', String(goal.progress.done)).replace('{total}', String(goal.progress.total))}
                </p>
                <DecisionCounts openQuestions={goal.progress.openQuestions} decided={goal.progress.decided ?? 0} variant="words"
                  className="text-sm" />
              </div>
              <GoalProjectsBar goalId={goal.id} projects={projects} workPackages={workPackages} />
            </Card>

            <div role="tablist" aria-label={t.goals.views.label} className="inline-flex max-w-full gap-1 overflow-x-auto p-1 rounded-lg bg-surface-secondary">
              {([
                ['outline', ListTree, t.goals.views.outline], ['list', List, t.goals.views.list],
                ['map', Network, t.goals.views.map], ['timeline', ChartGantt, t.goals.views.timeline],
              ] as const).map(([key, Icon, label]) => (
                <button key={key} type="button" role="tab" aria-selected={view === key} onClick={() => chooseView(key)}
                  className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md transition-colors ${
                    view === key ? 'bg-surface text-text shadow-sm font-medium' : 'text-text-secondary hover:text-text'}`}>
                  <Icon className="w-3.5 h-3.5 max-sm:hidden" aria-hidden="true" />
                  {label}
                </button>
              ))}
            </div>

            <DecisionContext.Provider value={decisionScope}>
              {view === 'outline' ? (
                <GoalOutline detail={data} today={today} onOpenTodo={setEditing} />
              ) : view === 'list' ? (
                <GoalTodoList goalId={goal.id} todos={todos} workPackages={workPackages} today={today} onOpenTodo={setEditing} />
              ) : view === 'timeline' ? (
                <GoalTimeline goal={goal} todos={todos} workPackages={workPackages} milestones={milestones} projects={projects}
                  legacyBackend={data.legacyBackend} onOpenTodo={setEditing} />
              ) : relations.isLoading || !relations.data ? (
                relations.error
                  ? apiErrorStatus(relations.error) === 404
                    ? <GoalMapOutdatedBackend onShowList={() => chooseView('list')} />
                    : <ErrorState message={relations.error.message} onRetry={() => relations.refetch()} />
                  : <div className="flex justify-center py-8"><Spinner text={t.common.loading} /></div>
              ) : (
                <GoalRelationMap relations={relations.data} onOpenTodo={setEditing} onAssign={assignTodo} badges={badges} />
              )}
            </DecisionContext.Provider>

            <GoalCandidates
              filter={filter}
              onFilterChange={setFilter}
              candidates={candidates}
              isLoading={candidatesPage.isLoading}
              suggestions={suggestions}
              onAssign={assignTodo}
              assigning={assign.isPending}
              scoped={candidatesPage.data?.scoped ?? false}
              allProjects={allProjects}
              onAllProjectsChange={setAllProjects}
              goalProjectIds={projects.map((project) => project.projectId)}
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
          {selected && (
            <DecisionContext.Provider value={decisionScope}>
              <GoalSidePanel detail={data} selection={selected} today={today} onClose={closeSelection} onSelect={select}
                onOpenTodo={setEditing} />
            </DecisionContext.Provider>
          )}
        </div>
      </GoalSelectionContext.Provider>
    </GoalWorkflowsProvider>
  );
}
