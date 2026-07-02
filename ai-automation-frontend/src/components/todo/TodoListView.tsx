import { memo } from 'react';
import { Link } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Pencil, Trash2, Clock, RefreshCw, ExternalLink, CalendarClock, Wrench } from 'lucide-react';
import { Badge } from '../ui';
import { useT } from '../../i18n';
import { ProviderIcon } from '../shared/ProviderIcon';
import { StatusDots } from './StatusDots';
import { priorityColor, sourceIcon, formatRelative } from './todoConstants';
import type { Todo, TodoStatus } from '../../types';

const MemoizedMarkdown = memo(function MemoizedMarkdown({ children }: { children: string }) {
  return <ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown>;
});

export const TodoListItem = memo(function TodoListItem({ todo, onEdit, onStatusChange, onDelete, onResolve, resolveLoading, exportedIcon }: {
  todo: Todo;
  onEdit: (todo: Todo) => void;
  onStatusChange: (todo: Todo, status: TodoStatus) => void;
  onDelete: (id: string) => void;
  onResolve: (todo: Todo) => void;
  resolveLoading?: boolean;
  exportedIcon?: string;
}) {
  const t = useT();

  return (
    <div
      className={`flex items-start gap-3 px-4 py-3 hover:bg-surface-raised/50 transition-colors cursor-pointer group ${
        todo.status === 'DONE' || todo.status === 'BACKLOG' ? 'opacity-50' : ''
      }`}
      onClick={() => onEdit(todo)}
    >
      {/* Status dots */}
      <div className="pt-0.5">
        <StatusDots current={todo.status} onChange={(s) => onStatusChange(todo, s)} />
      </div>

      {/* Priority bar */}
      <div className={`w-1 rounded-full shrink-0 self-stretch min-h-[2.5rem] ${priorityColor[todo.priority]}`} />

      {/* Content — two lines */}
      <div className="flex-1 min-w-0">
        {/* Line 1: Title + IN_PROGRESS badge */}
        <div className="flex items-center gap-2">
          <span className={`text-sm font-medium text-text leading-snug ${todo.status === 'DONE' ? 'line-through' : ''}`}>
            {todo.title}
          </span>
          {todo.status === 'IN_PROGRESS' && (
            <Badge variant="warning">{t.todos.statuses.IN_PROGRESS}</Badge>
          )}
          {todo.status === 'BACKLOG' && (
            <Badge variant="default">{t.todos.statuses.BACKLOG}</Badge>
          )}
        </div>

        {/* Line 2: Description + meta */}
        <div className="flex items-center gap-2 mt-1 flex-wrap">
          {todo.description && (
            <div className="text-xs text-text-tertiary line-clamp-2 flex-1 min-w-[120px] [&_a]:text-accent [&_a]:underline [&_p]:m-0 [&_code]:text-[10px] [&_code]:bg-surface-raised [&_code]:px-1 [&_code]:rounded">
              <MemoizedMarkdown>{todo.description}</MemoizedMarkdown>
            </div>
          )}
          {!todo.description && <div className="flex-1" />}

          <div className="flex items-center gap-1.5 shrink-0 flex-wrap">
            {/* Project badge */}
            {todo.projectName && (
              <span className="px-1.5 py-0.5 rounded text-[10px] bg-accent/10 text-accent font-medium">
                {todo.projectName}
              </span>
            )}

            {/* Source badge */}
            {todo.source !== 'USER' && (
              <Badge variant={todo.source === 'AI' ? 'warning' : 'default'}>
                <span className="flex items-center gap-1">
                  {sourceIcon(todo.source)}
                  {todo.sourceAgent || t.todos.sources[todo.source]}
                </span>
              </Badge>
            )}

            {/* Run link */}
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

            {/* Exported-to-tracker indicator (only when actually exported) */}
            {todo.exportedProvider && (
              <a
                href={todo.exportedUrl ?? undefined}
                target="_blank"
                rel="noreferrer"
                onClick={(e) => e.stopPropagation()}
                className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] bg-accent/10 text-accent font-medium hover:bg-accent/20 transition-colors"
                title={`${t.todos.alreadyExported}: ${todo.exportedRef ?? ''}`}
              >
                <ProviderIcon icon={exportedIcon} className="w-3 h-3" />
              </a>
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

            {/* Tags */}
            {todo.tags.length > 0 && (
              <div className="hidden sm:flex items-center gap-1">
                {todo.tags.slice(0, 2).map((tag) => (
                  <span key={tag} className="px-1.5 py-0.5 rounded text-[10px] bg-surface-raised text-text-tertiary border border-border">
                    {tag}
                  </span>
                ))}
                {todo.tags.length > 2 && (
                  <span className="text-[10px] text-text-tertiary">+{todo.tags.length - 2}</span>
                )}
              </div>
            )}

            {/* Due date */}
            {todo.dueDate && (() => {
              const now = new Date();
              const due = new Date(todo.dueDate);
              const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
              const dueDay = new Date(due.getFullYear(), due.getMonth(), due.getDate());
              const isOverdue = dueDay < today && todo.status !== 'DONE';
              const isDueToday = dueDay.getTime() === today.getTime() && todo.status !== 'DONE';
              const colorClass = isOverdue ? 'text-danger' : isDueToday ? 'text-warning' : 'text-text-tertiary';
              const label = isOverdue ? t.todos.overdue : isDueToday ? t.todos.dueToday : t.todos.dueDate;
              return (
                <span className={`text-xs shrink-0 inline-flex items-center gap-0.5 font-medium ${colorClass}`} title={`${label}: ${dueDay.toLocaleDateString()}`}>
                  <CalendarClock className="w-3 h-3" />
                  {dueDay.toLocaleDateString()}
                </span>
              );
            })()}

            {/* Time */}
            <span className="text-xs text-text-tertiary shrink-0 inline-flex items-center gap-0.5" title={`Erstellt: ${new Date(todo.createdAt).toLocaleString()}`}>
              <Clock className="w-3 h-3" />
              {formatRelative(todo.createdAt)}
            </span>
            {todo.updatedAt && Math.abs(new Date(todo.updatedAt).getTime() - new Date(todo.createdAt).getTime()) > 1000 && (
              <span className="text-xs text-text-tertiary shrink-0 inline-flex items-center gap-0.5" title={`Aktualisiert: ${new Date(todo.updatedAt).toLocaleString()}`}>
                <RefreshCw className="w-3 h-3" />
                {formatRelative(todo.updatedAt)}
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Actions */}
      <div className="flex items-center gap-1 shrink-0 pt-0.5 sm:opacity-0 sm:group-hover:opacity-100 transition-opacity">
        {todo.linkedWorkflowId && todo.status !== 'DONE' && (
          <button
            onClick={(e) => { e.stopPropagation(); onResolve(todo); }}
            disabled={resolveLoading || todo.resolutionState === 'RUNNING'}
            className="p-1.5 rounded-lg text-warning hover:text-warning disabled:opacity-50 transition-colors"
            aria-label={t.todos.resolve}
            title={t.todos.resolve}
          >
            <Wrench className="w-3.5 h-3.5" />
          </button>
        )}
        <button
          onClick={(e) => { e.stopPropagation(); onEdit(todo); }}
          className="p-1.5 rounded-lg text-text-tertiary hover:text-accent transition-colors"
          aria-label={t.common.edit}
        >
          <Pencil className="w-3.5 h-3.5" />
        </button>
        <button
          onClick={(e) => { e.stopPropagation(); onDelete(todo.id); }}
          className="p-1.5 rounded-lg text-text-tertiary hover:text-danger transition-colors"
          aria-label={t.common.delete}
        >
          <Trash2 className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  );
});
