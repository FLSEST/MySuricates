import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Sparkles, Loader2 } from 'lucide-react';
import { useT } from '../i18n';
import { useTodos, useCreateTodo, useUpdateTodoStatus, useUpdateTodo, useDeleteTodo, useResolveTodo } from '../hooks/useTodos';
import { Spinner, Button, Modal, Input, Select } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { EmptyState } from '../components/shared/EmptyState';
import { SimplePage, SimplePageHeader, SectionLabel, SimpleRow } from '../components/simple/kit';
import { StatusDots } from '../components/todo/StatusDots';
import { AREA_COLORS } from '../utils/homeTiles';
import type { Todo, TodoPriority } from '../types';

const PRIORITY_COLOR: Record<string, string> = {
  CRITICAL: 'var(--color-danger)', HIGH: 'var(--color-warning)', MEDIUM: 'var(--color-info)', LOW: 'var(--color-text-tertiary)',
};
const PRIO_RANK: Record<string, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };
const PRIORITIES: TodoPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

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
  const updateTodo = useUpdateTodo();
  const deleteTodo = useDeleteTodo();
  const resolve = useResolveTodo();

  const [newTitle, setNewTitle] = useState('');
  const [selected, setSelected] = useState<Todo | null>(null);
  const [draft, setDraft] = useState({ title: '', description: '', priority: 'MEDIUM' as TodoPriority, dueDate: '' });

  const isLoading = openQ.isLoading || inProgQ.isLoading;
  const error = openQ.error || inProgQ.error;

  const active = useMemo(
    () => [...(openQ.data?.content ?? []), ...(inProgQ.data?.content ?? [])],
    [openQ.data, inProgQ.data],
  );

  // Einmal beim Mounten festgehalten: Date.now() im Render ist unrein (react-hooks 7.1).
  const [now] = useState(() => Date.now());
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

  const openDetail = (td: Todo) => {
    setSelected(td);
    setDraft({
      title: td.title,
      description: td.description ?? '',
      priority: td.priority,
      dueDate: td.dueDate ? td.dueDate.slice(0, 10) : '',
    });
  };

  const saveEdit = () => {
    if (!selected) return;
    updateTodo.mutate(
      {
        id: selected.id,
        data: {
          title: draft.title.trim() || selected.title,
          description: draft.description,
          priority: draft.priority,
          dueDate: draft.dueDate ? new Date(draft.dueDate + 'T23:59:59').toISOString() : undefined,
        },
      },
      { onSuccess: () => setSelected(null) },
    );
  };

  const removeTodo = () => {
    if (!selected) return;
    if (window.confirm(t.common.deleteConfirm)) {
      deleteTodo.mutate(selected.id, { onSuccess: () => setSelected(null) });
    }
  };

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
        <span className="w-1 h-5 rounded-full shrink-0" style={{ background: PRIORITY_COLOR[td.priority] ?? 'var(--color-text-tertiary)' }} />
        <button
          onClick={() => openDetail(td)}
          className="text-sm font-medium text-text truncate text-left min-w-0 flex-1 hover:text-accent transition-colors"
        >
          {td.title}
        </button>
        {isOverdue(td) && <span className="text-[11px] font-medium shrink-0" style={{ color: 'var(--color-danger-text)' }}>{t.simple.todos.overdue}</span>}
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
        title={t.simple.todos.editTitle}
        footer={
          selected ? (
            <>
              <Button variant="ghost" onClick={removeTodo} loading={deleteTodo.isPending}>{t.common.delete}</Button>
              {target && (
                <Button variant="secondary" onClick={() => { navigate(target); setSelected(null); }}>
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
              <Button onClick={saveEdit} loading={updateTodo.isPending}>{t.common.save}</Button>
            </>
          ) : undefined
        }
      >
        <div className="space-y-4">
          <Input label={t.simple.todos.titleLabel} value={draft.title} onChange={(e) => setDraft({ ...draft, title: e.target.value })} />
          <div>
            <label className="block text-sm font-medium text-text-secondary mb-1">{t.common.description}</label>
            <textarea
              value={draft.description}
              onChange={(e) => setDraft({ ...draft, description: e.target.value })}
              rows={3}
              className="w-full text-sm bg-surface border border-border rounded-lg px-3 py-2 text-text placeholder:text-text-tertiary focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Select
              label={t.simple.todos.priority}
              value={draft.priority}
              onChange={(e) => setDraft({ ...draft, priority: e.target.value as TodoPriority })}
              options={PRIORITIES.map((p) => ({ value: p, label: t.todos.priorities[p] }))}
            />
            <Input
              type="date"
              label={t.simple.todos.due}
              value={draft.dueDate}
              onChange={(e) => setDraft({ ...draft, dueDate: e.target.value })}
            />
          </div>
        </div>
      </Modal>
    </SimplePage>
  );
}
