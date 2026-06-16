import { Check } from 'lucide-react';
import { useT } from '../i18n';
import { useTodos, useUpdateTodoStatus } from '../hooks/useTodos';
import { Spinner } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { EmptyState } from '../components/shared/EmptyState';
import { SimplePage, SimplePageHeader, SimpleRow } from '../components/simple/kit';
import { AREA_COLORS } from '../utils/homeTiles';

const PRIORITY_COLOR: Record<string, string> = {
  CRITICAL: '#fb7185', HIGH: '#fbbf24', MEDIUM: '#38bdf8', LOW: '#5b6678',
};

export function SimpleTodos() {
  const t = useT();
  const { data: page, isLoading, error, refetch } = useTodos({ status: 'OPEN' }, 0, 50);
  const updateStatus = useUpdateTodoStatus();
  const todos = page?.content ?? [];

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }
  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <SimplePage>
      <SimplePageHeader title={t.simple.todos.title} subtitle={t.simple.todos.subtitle} />

      {todos.length === 0 ? (
        <EmptyState title={t.simple.todos.empty} description={t.simple.todos.emptyHint} />
      ) : (
        <div className="space-y-2">
          {todos.map((todo) => (
            <SimpleRow key={todo.id} color={AREA_COLORS.todos}>
              <div className="flex items-center gap-3 min-w-0">
                <button
                  aria-label={t.simple.todos.markDone}
                  onClick={() => updateStatus.mutate({ id: todo.id, status: 'DONE' })}
                  className="w-5 h-5 rounded-full border border-border hover:border-[#fb7185] flex items-center justify-center shrink-0 text-text-tertiary hover:text-[#fb7185] transition-colors"
                >
                  <Check className="w-3 h-3" />
                </button>
                <span
                  className="w-1 h-5 rounded-full shrink-0"
                  style={{ background: PRIORITY_COLOR[todo.priority] ?? '#5b6678' }}
                />
                <p className="text-sm font-medium text-text truncate">{todo.title}</p>
              </div>
            </SimpleRow>
          ))}
        </div>
      )}
    </SimplePage>
  );
}
