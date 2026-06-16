import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Sparkles, Loader2 } from 'lucide-react';
import { useT } from '../i18n';
import { useTodos, useCreateTodo, useUpdateTodoStatus, useResolveTodo } from '../hooks/useTodos';
import { Spinner, Button, Modal } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { EmptyState } from '../components/shared/EmptyState';
import { SimplePage, SimplePageHeader, SectionLabel, SimpleRow } from '../components/simple/kit';
import { StatusDots } from '../components/todo/StatusDots';
import { AREA_COLORS } from '../utils/homeTiles';
import { formatDateTime } from '../utils/formatTime';
import type { Todo } from '../types';

const PRIORITY_COLOR: Record<string, string> = {
  CRITICAL: '#fb7185', HIGH: '#fbbf24', MEDIUM: '#38bdf8', LOW: '#5b6678',
};
const PRIO_RANK: Record<string, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };

function linkTarget(todo: Todo): string | null {
  if (todo.linkedWorkflowId && todo.workflowRunId) return `/workflows/${todo.linkedWorkflowId}/run/${todo.workflowRunId}`;
  if (todo.linkedWorkflowId) return `/workflows/${todo.linkedWorkflowId}`;
  return null;
}

export function SimpleTodos() {
  const t = useT();
  const navigate = useNavigate();
  const openQ = useTodos({ status: 'OPEN' }, 0, 50);
  const inProgQ = useTodos({ status: 'IN_PROGRESS' }, 0, 50);
  const createTodo = useCreateTodo();
  const updateStatus = useUpdateTodoStatus();
  const resolve = useResolveTodo();

  const [newTitle, setNewTitle] = useState('');
  const [selected, setSelected] = useState<Todo | null>(null);

  const isLoading = openQ.isLoading || inProgQ.isLoading;
  const error = openQ.error || inProgQ.error;

  const active = useMemo(
    () => [...(openQ.data?.content ?? []), ...(inProgQ.data?.content ?? [])],
    [openQ.data, inProgQ.data],
  );

  const now = Date.now();
  const isOverdue = (td: Todo) => !!td.dueDate && new Date(td.dueDate).getTime() < now;
  const isResolving = (td: Todo) => td.resolutionState === 'RUNNING' || (td.status === 'IN_PROGRESS' && !!td.lastFixRunId);

  const { needsYou, rest } = useMemo(() => {
    const ny = active
      .filter((td) => isOverdue(td) || td.priority === 'HIGH' || td.priority === 'CRITICAL')
      .sort((a, b) => (isOverdue(b) ? 1 : 0) - (isOverdue(a) ? 1 : 0) || (PRIO_RANK[a.priority] ?? 9) - (PRIO_RANK[b.priority] ?? 9));
    const nyIds = new Set(ny.map((td) => td.id));
    return { needsYou: ny, rest: active.filter((td) => !nyIds.has(td.id)) };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active]);

  const submitNew = () => {
    const title = newTitle.trim();
    if (!title) return;
    createTodo.mutate({ title }, { onSuccess: () => setNewTitle('') });
  };

  const markDone = (id: string) => updateStatus.mutate({ id, status: 'DONE' });
  const doResolve = (id: string) =>
    resolve.mutate(id, { onSuccess: (run) => navigate(`/workflows/${run.workflowId}/run/${run.id}`) });

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }
  if (error) {
    return <ErrorState message={error.message} onRetry={() => { openQ.refetch(); inProgQ.refetch(); }} />;
  }

  const renderTask = (td: Todo) => (
    <SimpleRow key={td.id} color={AREA_COLORS.todos}>
      <div className="flex items-center gap-3 min-w-0 flex-1">
        <StatusDots current={td.status} onChange={(s) => updateStatus.mutate({ id: td.id, status: s })} />
        <span className="w-1 h-5 rounded-full shrink-0" style={{ background: PRIORITY_COLOR[td.priority] ?? '#5b6678' }} />
        <button
          onClick={() => setSelected(td)}
          className="text-sm font-medium text-text truncate text-left min-w-0 flex-1 hover:text-accent transition-colors"
        >
          {td.title}
        </button>
        {isOverdue(td) && <span className="text-[11px] font-medium shrink-0" style={{ color: '#fb7185' }}>{t.simple.todos.overdue}</span>}
      </div>
      {td.linkedWorkflowId && td.status !== 'DONE' && (
        isResolving(td) ? (
          <span className="flex items-center gap-1.5 text-xs text-text-tertiary shrink-0">
            <Loader2 className="w-3.5 h-3.5 animate-spin" />{t.simple.todos.resolving}
          </span>
        ) : (
          <Button
            size="sm"
            variant="secondary"
            icon={<Sparkles className="w-3.5 h-3.5" />}
            loading={resolve.isPending}
            onClick={() => doResolve(td.id)}
          >
            {t.simple.todos.resolve}
          </Button>
        )
      )}
    </SimpleRow>
  );

  const target = selected ? linkTarget(selected) : null;

  return (
    <SimplePage>
      <SimplePageHeader title={t.simple.todos.title} subtitle={t.simple.todos.subtitle} />

      {/* Quick-Add */}
      <form onSubmit={(e) => { e.preventDefault(); submitNew(); }} className="flex items-center gap-2">
        <input
          value={newTitle}
          onChange={(e) => setNewTitle(e.target.value)}
          placeholder={t.simple.todos.quickAddPlaceholder}
          className="flex-1 text-sm bg-surface border border-border rounded-lg px-3 py-2.5 text-text placeholder:text-text-tertiary focus:outline-none focus:ring-1 focus:ring-accent"
        />
        <Button type="submit" icon={<Plus className="w-4 h-4" />} loading={createTodo.isPending} disabled={!newTitle.trim()}>
          {t.simple.todos.add}
        </Button>
      </form>

      {active.length === 0 ? (
        <EmptyState title={t.simple.todos.empty} description={t.simple.todos.emptyHint} />
      ) : (
        <>
          {needsYou.length > 0 && (
            <div>
              <SectionLabel>{t.simple.todos.needsYou}</SectionLabel>
              <div className="space-y-2">{needsYou.map(renderTask)}</div>
            </div>
          )}
          {rest.length > 0 && (
            <div>
              <SectionLabel>{t.simple.todos.allTasks}</SectionLabel>
              <div className="space-y-2">{rest.map(renderTask)}</div>
            </div>
          )}
        </>
      )}

      <Modal
        open={!!selected}
        onClose={() => setSelected(null)}
        title={selected?.title ?? ''}
        footer={
          selected ? (
            <>
              {target && (
                <Button variant="ghost" onClick={() => { navigate(target); setSelected(null); }}>
                  {t.simple.todos.openLinked}
                </Button>
              )}
              {selected.linkedWorkflowId && selected.status !== 'DONE' && (
                <Button
                  variant="secondary"
                  icon={<Sparkles className="w-3.5 h-3.5" />}
                  onClick={() => { doResolve(selected.id); setSelected(null); }}
                >
                  {t.simple.todos.resolve}
                </Button>
              )}
              <Button onClick={() => { markDone(selected.id); setSelected(null); }}>
                {t.simple.todos.markDone}
              </Button>
            </>
          ) : undefined
        }
      >
        {selected && (
          <div className="space-y-3">
            {selected.description ? (
              <p className="text-sm text-text-secondary whitespace-pre-wrap">{selected.description}</p>
            ) : (
              <p className="text-sm text-text-tertiary italic">{t.simple.todos.noDescription}</p>
            )}
            <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-tertiary">
              <span>{t.simple.todos.priority}: {selected.priority}</span>
              {selected.projectName && <span>{selected.projectName}</span>}
              {selected.dueDate && <span>{t.simple.todos.due}: {formatDateTime(selected.dueDate)}</span>}
            </div>
          </div>
        )}
      </Modal>
    </SimplePage>
  );
}
