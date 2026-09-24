import { ListTodo } from 'lucide-react';
import { Button } from '../ui';
import { useApproveTodos } from '../../hooks/useWorkflows';
import { useT } from '../../i18n';
import type { PendingTodoItem } from '../../types';

/** Vom Run vorgeschlagene TODOs, die erst nach Freigabe angelegt werden. */
export function RunPendingTodosPanel({ runId, pendingTodos }: { runId: string; pendingTodos: PendingTodoItem[] | undefined }) {
  const t = useT();
  const approveTodos = useApproveTodos();
  if (!pendingTodos?.length) return null;
  return (
    <div className="rounded-xl border border-accent/40 bg-accent/5 p-4 space-y-3">
      <div className="flex items-center gap-2">
        <ListTodo className="w-5 h-5 text-accent" />
        <h3 className="text-sm font-semibold text-text">{t.workflows.pendingTodos}</h3>
      </div>
      <p className="text-sm text-text-secondary">{t.workflows.pendingTodosHint}</p>
      <div className="space-y-2">
        {pendingTodos.map((todo, i) => (
          <div key={i} className="flex items-start gap-2 bg-surface rounded-lg px-3 py-2">
            <span className={`text-xs font-medium px-1.5 py-0.5 rounded shrink-0 ${
              todo.priority === 'HIGH' ? 'bg-danger/10 text-danger' :
              todo.priority === 'MEDIUM' ? 'bg-warning/10 text-warning' :
              'bg-surface-raised text-text-tertiary'
            }`}>{todo.priority}</span>
            <div className="min-w-0">
              <p className="text-sm font-medium text-text">{todo.title}</p>
              {todo.description && (
                <p className="text-xs text-text-secondary mt-0.5 line-clamp-2">{todo.description}</p>
              )}
            </div>
          </div>
        ))}
      </div>
      <div className="flex items-center gap-2 pt-1">
        <Button
          size="sm"
          onClick={() => approveTodos.mutate({ runId, approved: true })}
          loading={approveTodos.isPending}
        >
          {t.workflows.approveTodos}
        </Button>
        <Button
          size="sm"
          variant="ghost"
          onClick={() => approveTodos.mutate({ runId, approved: false })}
          loading={approveTodos.isPending}
        >
          {t.workflows.rejectTodos}
        </Button>
      </div>
    </div>
  );
}
