import { useNavigate } from 'react-router-dom';
import { LayoutGrid, FolderKanban, Archive, AlertTriangle, ChevronRight, Clock } from 'lucide-react';
import { Card, Button, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { TodoProgressBar } from '../components/todo/TodoProgressBar';
import { priorityColor, sourceIcon, formatRelative } from '../components/todo/todoConstants';
import { useTodoOverview } from '../hooks/useTodoOverview';
import { useArchiveStale } from '../hooks/useTodos';
import { useT } from '../i18n';
import type { NeedsYouItem, ProjectRollup } from '../types';

export function ProjectCockpit() {
  const t = useT();
  const navigate = useNavigate();
  const { data, isLoading, error, refetch } = useTodoOverview();
  const archiveStale = useArchiveStale();

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

      {/* "Braucht dich" focus lane */}
      <section>
        <div className="flex items-center gap-2 mb-3">
          <AlertTriangle className="w-4 h-4 text-warning" />
          <h2 className="text-sm font-semibold text-text uppercase tracking-wide">{t.cockpit.needsYou}</h2>
          {needsYou.length > 0 && (
            <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-warning/15 text-warning">
              {needsYou.length}
            </span>
          )}
        </div>
        {needsYou.length === 0 ? (
          <Card>
            <p className="text-sm text-text-tertiary text-center py-2">{t.cockpit.needsYouEmpty}</p>
          </Card>
        ) : (
          <Card padding={false}>
            <div className="divide-y divide-border">
              {needsYou.map((item) => (
                <NeedsYouRow key={item.id} item={item} onClick={() => gotoProject(item.projectId)} t={t} />
              ))}
            </div>
          </Card>
        )}
      </section>

      {/* Per-project rollup cards */}
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
          {item.overdue ? t.cockpit.overdue : formatRelative(item.dueDate)}
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
  return (
    <Card className="flex flex-col gap-3 hover:border-border-strong transition-colors">
      {/* Title row (click → project) */}
      <button onClick={onOpen} className="flex items-center justify-between text-left group">
        <h3 className="font-semibold text-text truncate group-hover:text-accent transition-colors">
          {rollup.projectName ?? t.cockpit.noProject}
        </h3>
        <span className="shrink-0 ml-2 text-xs text-text-tertiary">{active} {t.cockpit.active}</span>
      </button>

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
