import { Link } from 'react-router-dom';
import { ExternalLink } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import { formatTimestamp } from '../../utils/localeFormat';
import type { Todo } from '../../types';

/** Read-only facts of a todo in the edit dialog: origin, session, linked runs and workflow, dates. */
export function TodoMetadata({ todo }: { todo: Todo }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);

  return (
    <div className="pt-3 border-t border-border space-y-1">
      <p className="text-xs text-text-tertiary">
        {t.todos.source}: {t.todos.sources[todo.source]}
        {todo.sourceAgent && ` (${todo.sourceAgent})`}
      </p>
      {todo.sessionId && (
        <p className="text-xs text-text-tertiary">
          {t.todos.sessionId}: {todo.sessionId}
        </p>
      )}
      {todo.workflowRunId && (
        <div className="flex items-center gap-2 text-xs text-text-tertiary">
          <span>Run:</span>
          <Link
            to={`/runs/${todo.workflowRunId}`}
            className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] bg-accent/10 text-accent font-medium hover:bg-accent/20 transition-colors"
            title={t.todos.viewRun}
          >
            <ExternalLink className="w-2.5 h-2.5" />
            Run
          </Link>
        </div>
      )}
      {todo.lastFixRunId && (
        <div className="flex items-center gap-2 text-xs text-text-tertiary">
          <span>{t.todos.viewFixRun}:</span>
          <Link
            to={`/runs/${todo.lastFixRunId}`}
            className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] bg-warning/10 text-warning font-medium hover:bg-warning/20 transition-colors"
            title={t.todos.viewFixRun}
          >
            <ExternalLink className="w-2.5 h-2.5" />
            {t.todos.fixRun}
          </Link>
        </div>
      )}
      {todo.linkedWorkflowId && (
        <div className="flex items-center gap-2 text-xs text-text-tertiary">
          <span>{t.todos.linkedWorkflow}:</span>
          <Link
            to={`/workflows/${todo.linkedWorkflowId}`}
            className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] bg-accent/10 text-accent font-medium hover:bg-accent/20 transition-colors"
            title={todo.linkedWorkflowName ?? t.common.workflow}
          >
            <ExternalLink className="w-2.5 h-2.5" />
            {todo.linkedWorkflowName ?? t.common.workflow}
          </Link>
        </div>
      )}
      <p className="text-xs text-text-tertiary">
        {t.common.created}: {formatTimestamp(todo.createdAt, locale)}
      </p>
      <p className="text-xs text-text-tertiary">
        {t.common.updated}: {formatTimestamp(todo.updatedAt, locale)}
      </p>
    </div>
  );
}
