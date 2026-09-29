import { memo } from 'react';
import { Link } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Clock, RefreshCw, ExternalLink, CalendarClock, Wrench } from 'lucide-react';
import { Badge, Spinner } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { priorityColor } from './todoConstants';
import { formatRelativeShort } from '../../utils/formatTime';
import { formatDate, formatTimestamp } from '../../utils/localeFormat';
import type { Todo, TodoStatus } from '../../types';

const MemoizedMarkdown = memo(function MemoizedMarkdown({ children }: { children: string }) {
  return <ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown>;
});

/* ─── Kanban Card ───────────────────────────────────────── */
const KanbanCard = memo(function KanbanCard({ todo, onEdit, onStatusChange, onResolve, resolveLoading, highlighted }: {
  todo: Todo;
  onEdit: (todo: Todo) => void;
  onStatusChange: (todo: Todo, status: TodoStatus) => void;
  onResolve: (todo: Todo) => void;
  resolveLoading?: boolean;
  /** Deep-linked card — see TodoListItem. */
  highlighted?: boolean;
}) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  return (
    <div
      id={`todo-${todo.id}`}
      className={`bg-surface-base border rounded-lg p-3 transition-colors cursor-pointer group scroll-mt-4 ${
        highlighted ? 'border-accent/40 bg-accent/10' : 'border-border hover:border-border-strong'
      }`}
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
            {todo.linkedWorkflowId && (
              <Link
                to={`/workflows/${todo.linkedWorkflowId}`}
                onClick={(e) => e.stopPropagation()}
                className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] bg-warning/10 text-warning font-medium hover:bg-warning/20 transition-colors"
                title={todo.linkedWorkflowName ?? t.todos.linkedWorkflow}
              >
                <Wrench className="w-2.5 h-2.5" />
                {todo.linkedWorkflowName ?? t.todos.linkedWorkflow}
              </Link>
            )}
            {todo.resolutionState && todo.resolutionState !== 'IDLE' && (
              <Badge
                variant={
                  todo.resolutionState === 'RUNNING'
                    ? 'warning'
                    : todo.resolutionState === 'FAILED'
                      ? 'danger'
                      : 'success'
                }
              >
                {t.todos.resolutionStates[todo.resolutionState]}
              </Badge>
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
        <div className="flex items-center gap-2">
          <span className="text-[10px] text-text-tertiary inline-flex items-center gap-0.5" title={`${t.common.created}: ${formatTimestamp(todo.createdAt, locale)}`}>
            <Clock className="w-2.5 h-2.5" />
            {formatRelativeShort(todo.createdAt, locale)}
          </span>
          {todo.dueDate && (() => {
            const now = new Date();
            const due = new Date(todo.dueDate);
            const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
            const dueDay = new Date(due.getFullYear(), due.getMonth(), due.getDate());
            const isOverdue = dueDay < today && todo.status !== 'DONE';
            const isDueToday = dueDay.getTime() === today.getTime() && todo.status !== 'DONE';
            const colorClass = isOverdue ? 'text-danger' : isDueToday ? 'text-warning' : 'text-text-tertiary';
            return (
              <span className={`text-[10px] inline-flex items-center gap-0.5 font-medium ${colorClass}`}>
                <CalendarClock className="w-2.5 h-2.5" />
                {formatDate(dueDay, locale)}
              </span>
            );
          })()}
        </div>
        {todo.updatedAt && Math.abs(new Date(todo.updatedAt).getTime() - new Date(todo.createdAt).getTime()) > 1000 && (
          <span className="text-[10px] text-text-tertiary inline-flex items-center gap-0.5" title={`${t.common.updated}: ${formatTimestamp(todo.updatedAt, locale)}`}>
            <RefreshCw className="w-2.5 h-2.5" />
            {formatRelativeShort(todo.updatedAt, locale)}
          </span>
        )}
        <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
          {todo.linkedWorkflowId && todo.status !== 'DONE' && (
            <button
              onClick={(e) => { e.stopPropagation(); onResolve(todo); }}
              disabled={resolveLoading || todo.resolutionState === 'RUNNING'}
              className="px-1.5 py-0.5 rounded text-[10px] text-warning hover:bg-warning/10 disabled:opacity-50 transition-colors"
            >
              {t.todos.resolve}
            </button>
          )}
          {todo.status !== 'OPEN' && (
            <button
              onClick={(e) => { e.stopPropagation(); onStatusChange(todo, 'OPEN'); }}
              className="px-1.5 py-0.5 rounded text-[10px] text-text-tertiary hover:bg-surface-raised transition-colors"
            >
              {t.todos.statusShort.OPEN}
            </button>
          )}
          {todo.status !== 'IN_PROGRESS' && (
            <button
              onClick={(e) => { e.stopPropagation(); onStatusChange(todo, 'IN_PROGRESS'); }}
              className="px-1.5 py-0.5 rounded text-[10px] text-warning hover:bg-warning/10 transition-colors"
            >
              {t.todos.statusShort.IN_PROGRESS}
            </button>
          )}
          {todo.status !== 'DONE' && (
            <button
              onClick={(e) => { e.stopPropagation(); onStatusChange(todo, 'DONE'); }}
              className="px-1.5 py-0.5 rounded text-[10px] text-success hover:bg-success/10 transition-colors"
            >
              {t.todos.statusShort.DONE}
            </button>
          )}
          {todo.status !== 'BACKLOG' && (
            <button
              onClick={(e) => { e.stopPropagation(); onStatusChange(todo, 'BACKLOG'); }}
              className="px-1.5 py-0.5 rounded text-[10px] text-text-tertiary/60 hover:bg-text-tertiary/10 transition-colors"
            >
              {t.todos.statusShort.BACKLOG}
            </button>
          )}
        </div>
      </div>
    </div>
  );
});

/* ─── Kanban Board ──────────────────────────────────────── */

/** One column = the result of its own `status=` query (see useTodoColumn). */
export interface TodoKanbanColumn {
  status: TodoStatus;
  /** The cards loaded for this column (at most the configured column size). */
  todos: Todo[];
  /** Total number of todos with this status on the server — drives the truncation hint. */
  total: number;
  isLoading?: boolean;
}

const COLUMN_STYLE: Record<TodoStatus, { color: string; borderColor: string }> = {
  OPEN: { color: 'text-text-tertiary', borderColor: 'border-text-tertiary/30' },
  IN_PROGRESS: { color: 'text-warning', borderColor: 'border-warning/30' },
  DONE: { color: 'text-success', borderColor: 'border-success/30' },
  BACKLOG: { color: 'text-text-tertiary/60', borderColor: 'border-text-tertiary/20' },
  ARCHIVED: { color: 'text-text-tertiary/60', borderColor: 'border-text-tertiary/20' },
};

/** Static class names so Tailwind keeps them (no dynamic `md:grid-cols-${n}`). */
const GRID_COLS: Record<number, string> = {
  1: 'md:grid-cols-1',
  2: 'md:grid-cols-2',
  3: 'md:grid-cols-3',
  4: 'md:grid-cols-4',
  5: 'md:grid-cols-5',
};

export function TodoKanbanView({ columns, onEdit, onStatusChange, onResolve, resolveLoadingId, highlightId }: {
  columns: TodoKanbanColumn[];
  onEdit: (todo: Todo) => void;
  onStatusChange: (todo: Todo, status: TodoStatus) => void;
  onResolve: (todo: Todo) => void;
  resolveLoadingId?: string | null;
  highlightId?: string | null;
}) {
  const t = useT();

  return (
    <div className={`grid grid-cols-1 ${GRID_COLS[columns.length] ?? 'md:grid-cols-4'} gap-4`}>
      {columns.map(({ status, todos, total, isLoading }) => {
        const label = t.todos.statuses[status];
        const { color, borderColor } = COLUMN_STYLE[status];
        const loadedCount = todos.length;
        const isTruncated = total > loadedCount;
        return (
        <div
          key={status}
          data-testid={`kanban-column-${status}`}
          className={`rounded-xl border-2 ${borderColor} bg-surface-raised/30 p-3`}
        >
          {/* Column header */}
          <div className="flex items-center justify-between mb-3">
            <h3 className={`text-sm font-semibold ${color}`}>{label}</h3>
            <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${color} bg-surface-raised`}>
              {isTruncated ? `${loadedCount} / ${total}` : total}
            </span>
          </div>
          {/* Cards */}
          <div className="space-y-2">
            {isLoading && loadedCount === 0 ? (
              <Spinner size="sm" />
            ) : loadedCount === 0 ? (
              <div className="text-center py-6 text-xs text-text-tertiary">—</div>
            ) : (
              todos.map((todo) => (
                <KanbanCard
                  key={todo.id}
                  todo={todo}
                  onEdit={onEdit}
                  onStatusChange={onStatusChange}
                  onResolve={onResolve}
                  resolveLoading={resolveLoadingId === todo.id}
                  highlighted={highlightId === todo.id}
                />
              ))
            )}
            {isTruncated && (
              <p className="text-center py-2 text-[10px] text-text-tertiary">
                {t.todos.columnTruncated
                  .replace('{shown}', String(loadedCount))
                  .replace('{total}', String(total))
                  .replace('{label}', label)}
              </p>
            )}
          </div>
        </div>
        );
      })}
    </div>
  );
}
