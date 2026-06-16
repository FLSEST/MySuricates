import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Check } from 'lucide-react';
import { useT } from '../i18n';
import { useTodos, useUpdateTodoStatus } from '../hooks/useTodos';
import { Spinner, Button, Modal } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { EmptyState } from '../components/shared/EmptyState';
import { SimplePage, SimplePageHeader, SimpleRow } from '../components/simple/kit';
import { AREA_COLORS } from '../utils/homeTiles';
import { formatDateTime } from '../utils/formatTime';
import type { Todo } from '../types';

const PRIORITY_COLOR: Record<string, string> = {
  CRITICAL: '#fb7185', HIGH: '#fbbf24', MEDIUM: '#38bdf8', LOW: '#5b6678',
};

function linkTarget(todo: Todo): string | null {
  if (todo.linkedWorkflowId && todo.workflowRunId) return `/workflows/${todo.linkedWorkflowId}/run/${todo.workflowRunId}`;
  if (todo.linkedWorkflowId) return `/workflows/${todo.linkedWorkflowId}`;
  return null;
}

export function SimpleTodos() {
  const t = useT();
  const navigate = useNavigate();
  const { data: page, isLoading, error, refetch } = useTodos({ status: 'OPEN' }, 0, 50);
  const updateStatus = useUpdateTodoStatus();
  const todos = page?.content ?? [];
  const [selected, setSelected] = useState<Todo | null>(null);

  const markDone = (id: string) => updateStatus.mutate({ id, status: 'DONE' });

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }
  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  const target = selected ? linkTarget(selected) : null;

  return (
    <SimplePage>
      <SimplePageHeader title={t.simple.todos.title} subtitle={t.simple.todos.subtitle} />

      {todos.length === 0 ? (
        <EmptyState title={t.simple.todos.empty} description={t.simple.todos.emptyHint} />
      ) : (
        <div className="space-y-2">
          {todos.map((todo) => (
            <SimpleRow key={todo.id} color={AREA_COLORS.todos}>
              <div className="flex items-center gap-3 min-w-0 flex-1">
                <button
                  aria-label={t.simple.todos.markDone}
                  onClick={() => markDone(todo.id)}
                  className="w-5 h-5 rounded-full border border-border hover:border-[#fb7185] flex items-center justify-center shrink-0 text-text-tertiary hover:text-[#fb7185] transition-colors"
                >
                  <Check className="w-3 h-3" />
                </button>
                <span
                  className="w-1 h-5 rounded-full shrink-0"
                  style={{ background: PRIORITY_COLOR[todo.priority] ?? '#5b6678' }}
                />
                <button
                  onClick={() => setSelected(todo)}
                  className="text-sm font-medium text-text truncate text-left flex-1 min-w-0 hover:text-accent transition-colors"
                >
                  {todo.title}
                </button>
              </div>
            </SimpleRow>
          ))}
        </div>
      )}

      <Modal
        open={!!selected}
        onClose={() => setSelected(null)}
        title={selected?.title ?? ''}
        footer={
          selected ? (
            <>
              {target && (
                <Button
                  variant="secondary"
                  onClick={() => { navigate(target); setSelected(null); }}
                >
                  {t.simple.todos.openLinked}
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
