import { Hint } from '../components/hints/Hint';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { LayoutGrid, FolderKanban, Archive, AlertTriangle, ChevronRight, Clock, ArrowUpRight } from 'lucide-react';
import { Card, Button, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { TodoProgressBar } from '../components/todo/TodoProgressBar';
import { GoalsSection } from '../components/goal/GoalsSection';
import { MilestoneDueRow } from '../components/goal/MilestoneDueRow';
import { priorityColor, sourceIcon } from '../components/todo/todoConstants';
import { useTodoOverview } from '../hooks/useTodoOverview';
import { useModuleEnabled } from '../hooks/useModules';
import { useArchiveStale, useProjectActiveTodos } from '../hooks/useTodos';
import { todoDeepLink } from '../utils/todoLinks';
import { formatRelativeShort } from '../utils/formatTime';
import { useI18nStore, useT } from '../i18n';
import type { NeedsYouItem, ProjectRollup, Todo } from '../types';

export function ProjectCockpit() {
  const t = useT();
  const navigate = useNavigate();
  const { data, isLoading, error, refetch } = useTodoOverview();
  const archiveStale = useArchiveStale();
  // Ziele sind ein Modul: aus, gibt es weder fällige Meilensteine noch die Zielkarten (c60bf914)
  const goalsModule = useModuleEnabled('goals');

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }
  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  const needsYou = data?.needsYou ?? [];
  // Meilensteine, die bald fällig oder überfällig sind (TODO e192df18); ein älteres Backend liefert sie nicht.
  const milestonesDue = goalsModule ? data?.milestonesDue ?? [] : [];
  const attention = needsYou.length + milestonesDue.length;
  const projects = data?.projects ?? [];
  const staleThresholdDays = data?.staleThresholdDays ?? 30;

  const gotoProject = (projectId: string | null) =>
    navigate(projectId ? `/todos?projectId=${encodeURIComponent(projectId)}` : '/todos');

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="p-2 rounded-xl bg-accent/10 text-accent">
          <LayoutGrid className="w-5 h-5" />
        </div>
        <div>
          <h1 className="text-2xl font-bold text-text">{t.cockpit.title}</h1>
          <p className="text-sm text-text-secondary mt-0.5">{t.cockpit.subtitle}</p>
        </div>
      </div>

      <Hint id="cockpit-overview" />

      {/* "Braucht dich" focus lane: due milestones first, then the TODOs */}
      <section aria-labelledby="needs-you-heading">
        <div className="flex items-center gap-2 mb-3">
          <AlertTriangle className="w-4 h-4 text-warning" />
          <h2 id="needs-you-heading" className="text-sm font-semibold text-text uppercase tracking-wide">{t.cockpit.needsYou}</h2>
          {attention > 0 && (
            <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-warning/15 text-warning">
              {attention}
            </span>
          )}
        </div>
        {attention === 0 ? (
          <Card>
            <p className="text-sm text-text-tertiary text-center py-2">{t.cockpit.needsYouEmpty}</p>
          </Card>
        ) : (
          <Card padding={false}>
            <div className="divide-y divide-border">
              {milestonesDue.map((item) => <MilestoneDueRow key={item.id} item={item} />)}
              {needsYou.map((item) => (
                <NeedsYouRow key={item.id} item={item} onClick={() => navigate(todoDeepLink(item))} t={t} />
              ))}
            </div>
          </Card>
        )}
      </section>

      {/* Per-project rollup cards */}
      {goalsModule && <GoalsSection />}

      <section>
        <div className="flex items-center gap-2 mb-3">
          <FolderKanban className="w-4 h-4 text-text-secondary" />
          <h2 className="text-sm font-semibold text-text uppercase tracking-wide">{t.cockpit.projects}</h2>
          {projects.length > 0 && (
            <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-surface text-text-secondary">
              {projects.length}
            </span>
          )}
        </div>
        {projects.length === 0 ? (
          <EmptyState
            icon={<LayoutGrid className="w-8 h-8 text-text-tertiary" />}
            title={t.cockpit.empty}
          />
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {projects.map((p) => (
              <ProjectCard
                key={p.projectId ?? '__none__'}
                rollup={p}
                staleThresholdDays={staleThresholdDays}
                archiving={archiveStale.isPending && archiveStale.variables === (p.projectId ?? undefined)}
                onOpen={() => gotoProject(p.projectId)}
                onArchiveStale={() => archiveStale.mutate(p.projectId ?? undefined)}
                t={t}
              />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

/* ─── Needs-You row ─────────────────────────────────────── */
function NeedsYouRow({ item, onClick, t }: { item: NeedsYouItem; onClick: () => void; t: ReturnType<typeof useT> }) {
  const locale = useI18nStore((s) => s.locale);
  return (
    <button
      onClick={onClick}
      className="w-full flex items-center gap-3 px-4 py-2.5 text-left hover:bg-surface-raised transition-colors"
    >
      <span className={`w-2 h-2 rounded-full shrink-0 ${priorityColor[item.priority]}`} title={t.todos.priorities[item.priority]} />
      <span className="text-text-tertiary shrink-0">{sourceIcon(item.source)}</span>
      <span className="flex-1 min-w-0 truncate text-sm text-text">{item.title}</span>
      {item.projectName && (
        <span className="hidden sm:inline shrink-0 px-2 py-0.5 rounded-md text-xs bg-surface text-text-secondary">
          {item.projectName}
        </span>
      )}
      {item.dueDate && (
        <span className={`shrink-0 flex items-center gap-1 text-xs ${item.overdue ? 'text-danger font-medium' : 'text-text-tertiary'}`}>
          <Clock className="w-3 h-3" />
          {item.overdue ? t.cockpit.overdue : formatRelativeShort(item.dueDate, locale)}
        </span>
      )}
      <ChevronRight className="w-4 h-4 text-text-tertiary shrink-0" />
    </button>
  );
}

/* ─── Project card ──────────────────────────────────────── */
function ProjectCard({
  rollup, staleThresholdDays, archiving, onOpen, onArchiveStale, t,
}: {
  rollup: ProjectRollup;
  staleThresholdDays: number;
  archiving: boolean;
  onOpen: () => void;
  onArchiveStale: () => void;
  t: ReturnType<typeof useT>;
}) {
  const active = rollup.open + rollup.inProgress + rollup.backlog;
  const expandable = rollup.projectId != null;
  const [expanded, setExpanded] = useState(false);

  return (
    <Card className="flex flex-col gap-3 hover:border-border-strong transition-colors">
      {/* Title row — click navigates to the project's Todos */}
      <div className="flex items-center justify-between gap-2">
        <button
          onClick={onOpen}
          className="flex items-center gap-1 min-w-0 text-left group flex-1"
          title={t.cockpit.openProject}
        >
          <h3 className="font-semibold text-text truncate group-hover:text-accent transition-colors">
            {rollup.projectName ?? t.cockpit.noProject}
          </h3>
          <ArrowUpRight className="w-3.5 h-3.5 shrink-0 text-text-tertiary group-hover:text-accent transition-colors" />
        </button>
        <span className="shrink-0 text-xs text-text-tertiary">{active} {t.cockpit.active}</span>
      </div>

      {/* Progress bar */}
      <TodoProgressBar open={rollup.open} inProgress={rollup.inProgress} done={rollup.done} backlog={rollup.backlog} />

      {/* yours vs machine + needs-you */}
      <div className="flex items-center gap-3 text-xs text-text-secondary">
        <span className="flex items-center gap-1">
          <span className="w-2 h-2 rounded-full bg-accent" />
          {rollup.userActive} {t.cockpit.yours}
        </span>
        <span className="flex items-center gap-1">
          <span className="w-2 h-2 rounded-full bg-text-tertiary/50" />
          {rollup.machineActive} {t.cockpit.machine}
        </span>
        {rollup.needsYouCount > 0 && (
          <span className="flex items-center gap-1 text-warning ml-auto">
            <AlertTriangle className="w-3 h-3" />
            {rollup.needsYouCount}
          </span>
        )}
      </div>

      {/* Explicit, labeled toggle (not a bare chevron, not in the title) → lazily-loaded open + in-progress todos */}
      {expandable && (
        <div>
          <button
            onClick={() => setExpanded((v) => !v)}
            aria-expanded={expanded}
            className="flex items-center gap-1.5 text-xs font-medium text-accent hover:underline"
          >
            <ChevronRight className={`w-3.5 h-3.5 transition-transform ${expanded ? 'rotate-90' : ''}`} />
            {expanded ? t.cockpit.collapseTodos : t.cockpit.expandTodos}
          </button>
          {expanded && <ProjectTodoList projectId={rollup.projectId!} onOpen={onOpen} t={t} />}
        </div>
      )}

      {/* Stale badge + archive action */}
      {rollup.staleCount > 0 && (
        <div className="flex items-center justify-between pt-2 border-t border-border">
          <span className="flex items-center gap-1.5 text-xs text-text-tertiary" title={t.cockpit.staleHint.replace('{days}', String(staleThresholdDays))}>
            <Archive className="w-3.5 h-3.5" />
            {rollup.staleCount} {t.cockpit.stale}
          </span>
          <Button
            variant="ghost"
            size="sm"
            loading={archiving}
            onClick={(e) => { e.stopPropagation(); onArchiveStale(); }}
          >
            {t.cockpit.archiveStale}
          </Button>
        </div>
      )}
    </Card>
  );
}

/* ─── Expanded project todo list (lazy) ─────────────────── */
function ProjectTodoList({ projectId, onOpen, t }: { projectId: string; onOpen: () => void; t: ReturnType<typeof useT> }) {
  const navigate = useNavigate();
  const { data, isLoading } = useProjectActiveTodos(projectId, true);
  const todos = data?.content ?? [];
  const total = data?.totalElements ?? 0;
  const more = total - todos.length;

  return (
    <div className="pt-2 border-t border-border">
      {isLoading ? (
        <div className="py-3 flex justify-center"><Spinner size="sm" /></div>
      ) : todos.length === 0 ? (
        <p className="py-2 text-xs text-text-tertiary text-center">{t.cockpit.noOpenTodos}</p>
      ) : (
        <div className="-mx-1">
          {todos.map((todo) => (
            <TodoMiniRow key={todo.id} todo={todo} onClick={() => navigate(todoDeepLink(todo))} t={t} />
          ))}
          <button
            onClick={onOpen}
            className="w-full mt-1 px-1 py-1.5 text-left text-xs text-accent hover:underline"
          >
            {more > 0 ? `${t.cockpit.moreTodos.replace('{n}', String(more))} · ${t.cockpit.openProject}` : `${t.cockpit.openProject} →`}
          </button>
        </div>
      )}
    </div>
  );
}

/* ─── Compact read-only todo row ────────────────────────── */
function TodoMiniRow({ todo, onClick, t }: { todo: Todo; onClick: () => void; t: ReturnType<typeof useT> }) {
  const locale = useI18nStore((s) => s.locale);
  return (
    <button
      onClick={onClick}
      className="w-full flex items-center gap-2 px-1 py-1.5 text-left rounded-md hover:bg-surface-raised transition-colors"
    >
      <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${priorityColor[todo.priority]}`} title={t.todos.priorities[todo.priority]} />
      <span className="text-text-tertiary shrink-0">{sourceIcon(todo.source)}</span>
      <span className="flex-1 min-w-0 truncate text-xs text-text">{todo.title}</span>
      {todo.status === 'IN_PROGRESS' && (
        <span className="shrink-0 text-[10px] px-1.5 py-0.5 rounded bg-warning/15 text-warning">{t.todos.statuses.IN_PROGRESS}</span>
      )}
      <span className="shrink-0 text-[11px] text-text-tertiary">{formatRelativeShort(todo.updatedAt, locale)}</span>
    </button>
  );
}
