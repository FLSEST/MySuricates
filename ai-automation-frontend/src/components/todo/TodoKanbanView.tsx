import { memo, useMemo } from 'react';
import { Link } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Clock, RefreshCw, ExternalLink } from 'lucide-react';
import { useT } from '../../i18n';
import { priorityColor, formatRelative } from './todoConstants';
import type { Todo, TodoStatus } from '../../types';

const MemoizedMarkdown = memo(function MemoizedMarkdown({ children }: { children: string }) {
  return <ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown>;
});

/* ─── Kanban Card ───────────────────────────────────────── */
const KanbanCard = memo(function KanbanCard({ todo, onEdit, onStatusChange }: {
  todo: Todo;
  onEdit: (todo: Todo) => void;
  onStatusChange: (todo: Todo, status: TodoStatus) => void;
}) {
  const t = useT();
  return (
    <div
      className="bg-surface-base border border-border rounded-lg p-3 hover:border-border-strong transition-colors cursor-pointer group"
      onClick={() => onEdit(todo)}
    >
      <div className="flex gap-2">
        {/* Priority bar */}
        <div className={`w-1 rounded-full shrink-0 self-stretch ${priorityColor[todo.priority]}`} />
        <div className="flex-1 min-w-0">
          {/* Title */}
          <p className="text-sm font-medium text-text leading-snug">{todo.title}</p>
          {/* Description */}
          {todo.description && (
            <div className="text-xs text-text-tertiary mt-1 line-clamp-2 [&_a]:text-accent [&_a]:underline [&_p]:m-0 [&_code]:text-[10px] [&_code]:bg-surface-raised [&_code]:px-1 [&_code]:rounded">
              <MemoizedMarkdown>{todo.description}</MemoizedMarkdown>
            </div>
          )}
          {/* Bottom row: badges */}
          <div className="flex flex-wrap items-center gap-1.5 mt-2">
            {todo.projectName && (
              <span className="px-1.5 py-0.5 rounded text-[10px] bg-accent/10 text-accent font-medium">
                {todo.projectName}
              </span>
            )}
            {todo.workflowRunId && (
              <Link
                to={`/runs/${todo.workflowRunId}`}
                onClick={(e) => e.stopPropagation()}
                className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] bg-accent/10 text-accent font-medium hover:bg-accent/20 transition-colors"
                title={t.todos.viewRun}
              >
                <ExternalLink className="w-2.5 h-2.5" />
                Run
              </Link>
            )}
            {todo.tags.slice(0, 3).map((tag) => (
              <span key={tag} className="px-1.5 py-0.5 rounded text-[10px] bg-surface-raised text-text-tertiary border border-border">
                {tag}
              </span>
            ))}
            {todo.tags.length > 3 && (
              <span className="text-[10px] text-text-tertiary">+{todo.tags.length - 3}</span>
            )}
          </div>
        </div>
      </div>
      {/* Quick status change buttons (on hover) */}
      <div className="flex items-center justify-between mt-2 pt-2 border-t border-border/50">
        <span className="text-[10px] text-text-tertiary inline-flex items-center gap-0.5" title={`Erstellt: ${new Date(todo.createdAt).toLocaleString()}`}>
          <Clock className="w-2.5 h-2.5" />
          {formatRelative(todo.createdAt)}
        </span>
        {todo.updatedAt && Math.abs(new Date(todo.updatedAt).getTime() - new Date(todo.createdAt).getTime()) > 1000 && (
          <span className="text-[10px] text-text-tertiary inline-flex items-center gap-0.5" title={`Aktualisiert: ${new Date(todo.updatedAt).toLocaleString()}`}>
            <RefreshCw className="w-2.5 h-2.5" />
            {formatRelative(todo.updatedAt)}
          </span>
        )}
        <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
          {todo.status !== 'OPEN' && (
            <button
              onClick={(e) => { e.stopPropagation(); onStatusChange(todo, 'OPEN'); }}
              className="px-1.5 py-0.5 rounded text-[10px] text-text-tertiary hover:bg-surface-raised transition-colors"
            >
              Open
            </button>
          )}
          {todo.status !== 'IN_PROGRESS' && (
            <button
              onClick={(e) => { e.stopPropagation(); onStatusChange(todo, 'IN_PROGRESS'); }}
              className="px-1.5 py-0.5 rounded text-[10px] text-warning hover:bg-warning/10 transition-colors"
            >
              WIP
            </button>
          )}
          {todo.status !== 'DONE' && (
            <button
              onClick={(e) => { e.stopPropagation(); onStatusChange(todo, 'DONE'); }}
              className="px-1.5 py-0.5 rounded text-[10px] text-success hover:bg-success/10 transition-colors"
            >
              Done
            </button>
          )}
          {todo.status !== 'BACKLOG' && (
            <button
              onClick={(e) => { e.stopPropagation(); onStatusChange(todo, 'BACKLOG'); }}
              className="px-1.5 py-0.5 rounded text-[10px] text-text-tertiary/60 hover:bg-text-tertiary/10 transition-colors"
            >
              Backlog
            </button>
          )}
        </div>
      </div>
    </div>
  );
});

/* ─── Kanban Board ──────────────────────────────────────── */
export function TodoKanbanView({ todos, onEdit, onStatusChange }: {
  todos: Todo[];
  onEdit: (todo: Todo) => void;
  onStatusChange: (todo: Todo, status: TodoStatus) => void;
}) {
  const t = useT();

  const columns: { status: TodoStatus; label: string; color: string; borderColor: string }[] = [
    { status: 'OPEN', label: t.todos.statuses.OPEN, color: 'text-text-tertiary', borderColor: 'border-text-tertiary/30' },
    { status: 'IN_PROGRESS', label: t.todos.statuses.IN_PROGRESS, color: 'text-warning', borderColor: 'border-warning/30' },
    { status: 'DONE', label: t.todos.statuses.DONE, color: 'text-success', borderColor: 'border-success/30' },
    { status: 'BACKLOG', label: t.todos.statuses.BACKLOG, color: 'text-text-tertiary/60', borderColor: 'border-text-tertiary/20' },
  ];

  const grouped = useMemo(() => {
    const map: Record<TodoStatus, Todo[]> = { OPEN: [], IN_PROGRESS: [], DONE: [], BACKLOG: [] };
    todos.forEach((todo) => map[todo.status].push(todo));
    return map;
  }, [todos]);

  return (
    <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
      {columns.map(({ status, label, color, borderColor }) => (
        <div key={status} className={`rounded-xl border-2 ${borderColor} bg-surface-raised/30 p-3`}>
          {/* Column header */}
          <div className="flex items-center justify-between mb-3">
            <h3 className={`text-sm font-semibold ${color}`}>{label}</h3>
            <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${color} bg-surface-raised`}>
              {grouped[status].length}
            </span>
          </div>
          {/* Cards */}
          <div className="space-y-2">
            {grouped[status].length === 0 ? (
              <div className="text-center py-6 text-xs text-text-tertiary">—</div>
            ) : (
              grouped[status].map((todo) => (
                <KanbanCard
                  key={todo.id}
                  todo={todo}
                  onEdit={onEdit}
                  onStatusChange={onStatusChange}
                />
              ))
            )}
          </div>
        </div>
      ))}
    </div>
  );
}
