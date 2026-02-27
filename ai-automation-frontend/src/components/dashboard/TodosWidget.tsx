import { useNavigate } from 'react-router-dom';
import { CheckSquare, ArrowRight, Circle, Bot, Workflow } from 'lucide-react';
import { Card, CardHeader } from '../ui';
import { useTodoCount, useTodos } from '../../hooks/useTodos';
import { useT } from '../../i18n';
import { formatRelativeTime } from '../../utils/formatTime';
import type { Todo, TodoPriority } from '../../types';

const priorityColor: Record<TodoPriority, string> = {
  HIGH: 'bg-danger',
  MEDIUM: 'bg-accent',
  LOW: 'bg-text-tertiary',
};

const sourceIcon: Record<string, React.ReactNode> = {
  AI: <Bot className="w-3 h-3" />,
  WORKFLOW: <Workflow className="w-3 h-3" />,
};

function TodoRow({ todo }: { todo: Todo }) {
  const navigate = useNavigate();

  return (
    <div
      className="flex items-center gap-3 px-5 py-2.5 hover:bg-surface transition-colors cursor-pointer group"
      onClick={() => navigate('/todos')}
    >
      {/* Priority indicator */}
      <div className={`w-1 h-8 rounded-full shrink-0 ${priorityColor[todo.priority]}`} />

      {/* Content */}
      <div className="min-w-0 flex-1">
        <p className="text-sm text-text truncate">{todo.title}</p>
        <div className="flex items-center gap-2 mt-0.5">
          {todo.source !== 'USER' && (
            <span className="flex items-center gap-1 text-[10px] text-text-tertiary">
              {sourceIcon[todo.source]}
              {todo.sourceAgent || todo.source}
            </span>
          )}
          {todo.projectName && (
            <span className="text-[10px] text-accent/70">{todo.projectName}</span>
          )}
          {todo.tags.length > 0 && (
            <span className="text-[10px] text-text-tertiary">
              {todo.tags.slice(0, 2).join(', ')}
              {todo.tags.length > 2 && ` +${todo.tags.length - 2}`}
            </span>
          )}
        </div>
      </div>

      {/* Time */}
      <span className="text-[10px] text-text-tertiary shrink-0">
        {formatRelativeTime(todo.createdAt)}
      </span>
    </div>
  );
}

export function TodosWidget() {
  const t = useT();
  const navigate = useNavigate();
  const { data: counts } = useTodoCount();
  const { data: recentTodosPage } = useTodos({ status: 'OPEN' }, 0, 5);

  const open = counts?.open ?? 0;
  const inProgress = counts?.inProgress ?? 0;
  const done = counts?.done ?? 0;
  const total = open + inProgress + done;

  // Show up to 5 most recent open todos
  const displayTodos = recentTodosPage?.content ?? [];

  return (
    <Card padding={false}>
      <div className="p-5 pb-0">
        <CardHeader
          title={t.dashboard.todosOverview}
          action={
            <button
              onClick={() => navigate('/todos')}
              className="flex items-center gap-1 text-xs text-accent hover:text-accent-hover transition-colors"
            >
              {t.dashboard.viewAllTodos}
              <ArrowRight className="w-3 h-3" />
            </button>
          }
        />

        {/* Count summary */}
        <div className="flex items-center gap-4 mb-4">
          {/* Mini progress bar */}
          <div className="flex-1 h-2 rounded-full bg-surface overflow-hidden flex">
            {total > 0 && (
              <>
                {done > 0 && (
                  <div
                    className="h-full bg-success transition-all duration-500"
                    style={{ width: `${(done / total) * 100}%` }}
                  />
                )}
                {inProgress > 0 && (
                  <div
                    className="h-full bg-accent transition-all duration-500"
                    style={{ width: `${(inProgress / total) * 100}%` }}
                  />
                )}
                {open > 0 && (
                  <div
                    className="h-full bg-text-tertiary/40 transition-all duration-500"
                    style={{ width: `${(open / total) * 100}%` }}
                  />
                )}
              </>
            )}
          </div>

          {/* Counts */}
          <div className="flex items-center gap-3 shrink-0 text-xs">
            <span className="flex items-center gap-1 text-text-tertiary">
              <Circle className="w-2 h-2 fill-text-tertiary/40 text-text-tertiary/40" />
              {open} {t.dashboard.todoOpen}
            </span>
            <span className="flex items-center gap-1 text-accent">
              <Circle className="w-2 h-2 fill-accent text-accent" />
              {inProgress} {t.dashboard.todoInProgress}
            </span>
            <span className="flex items-center gap-1 text-success">
              <Circle className="w-2 h-2 fill-success text-success" />
              {done} {t.dashboard.todoDone}
            </span>
          </div>
        </div>
      </div>

      {/* Recent open todos */}
      {displayTodos.length === 0 ? (
        <div className="px-5 pb-5 text-center py-6">
          <CheckSquare className="w-8 h-8 text-success/40 mx-auto mb-2" />
          <p className="text-sm text-text-tertiary">{t.dashboard.noOpenTodos}</p>
        </div>
      ) : (
        <div className="divide-y divide-border/50 border-t border-border/50">
          {displayTodos.map((todo) => (
            <TodoRow key={todo.id} todo={todo} />
          ))}
        </div>
      )}
    </Card>
  );
}
