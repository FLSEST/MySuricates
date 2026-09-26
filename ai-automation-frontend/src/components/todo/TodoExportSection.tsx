import { Link } from 'react-router-dom';
import { ExternalLink } from 'lucide-react';
import { Button } from '../ui';
import { useT } from '../../i18n';
import { useTodoExportTarget } from '../../hooks/useTodoExportTarget';
import { ProviderIcon } from '../shared/ProviderIcon';
import type { Todo, Project, IntegrationProviderInfo } from '../../types';

interface TodoExportSectionProps {
  todo: Todo;
  projectId?: string;
  projects: Project[];
  integrations: IntegrationProviderInfo[];
  onExport: (provider: string, target: string) => void;
  exportLoading?: boolean;
}

/** Transfer of a todo to a task tracker, or where it went, plus its internal reference. */
export function TodoExportSection({
  todo, projectId, projects, integrations, onExport, exportLoading,
}: TodoExportSectionProps) {
  const t = useT();
  const target = useTodoExportTarget({ todo, projectId, projects, integrations });
  const { taskSinks, taskTargets, activeProvider, activeTarget } = target;

  return (
    <div className="pt-3 border-t border-border space-y-2">
      <label className="block text-sm font-medium text-text-secondary">{t.todos.exportSection}</label>
      {todo.exportedProvider ? (
        <p className="text-xs text-text-secondary">
          {t.todos.exportedTo}: <ProviderIcon icon={target.exportedProviderIcon} className="w-3.5 h-3.5 inline-block align-text-bottom mr-0.5" /><span className="font-medium">{target.exportedProviderName}</span>{' '}
          {todo.exportedUrl ? (
            <a href={todo.exportedUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-0.5 text-accent hover:underline">
              {todo.exportedRef}<ExternalLink className="w-3 h-3" />
            </a>
          ) : (
            <span>{todo.exportedRef}</span>
          )}
        </p>
      ) : taskSinks.length === 0 ? (
        <Link to="/integrations" className="text-xs text-accent hover:underline">
          {t.todos.exportNoTarget}
        </Link>
      ) : (
        <div className="space-y-1.5">
          <div className="flex items-center gap-2">
            {taskSinks.length > 1 ? (
              <select
                className="input-base text-sm"
                value={activeProvider}
                onChange={(e) => target.setExportProvider(e.target.value)}
              >
                {taskSinks.map((s) => (
                  <option key={s.providerId} value={s.providerId}>{s.displayName}</option>
                ))}
              </select>
            ) : (
              <span className="inline-flex items-center gap-1 text-xs font-medium text-text-secondary shrink-0">
                <ProviderIcon icon={target.activeProviderIcon} className="w-3.5 h-3.5" />
                {target.activeProviderName}
              </span>
            )}
            {taskTargets && taskTargets.length > 0 ? (
              <select
                className="input-base text-sm flex-1"
                value={activeTarget}
                onChange={(e) => target.setExportTarget(e.target.value)}
              >
                <option value="">{t.todos.exportChooseTarget}</option>
                {activeTarget && !taskTargets.some((tt) => tt.id === activeTarget) && (
                  <option value={activeTarget}>{activeTarget}</option>
                )}
                {taskTargets.map((tt) => (
                  <option key={tt.id} value={tt.id}>{tt.label}</option>
                ))}
              </select>
            ) : (
              <input
                className="input-base text-sm flex-1"
                value={target.exportTarget}
                onChange={(e) => target.setExportTarget(e.target.value)}
                placeholder={target.configuredTarget || t.todos.exportTargetPlaceholder}
              />
            )}
            <Button size="sm" variant="secondary" loading={exportLoading} disabled={!activeTarget} onClick={() => onExport(activeProvider, activeTarget)}>
              {t.todos.exportToTracker}
            </Button>
          </div>
          {!activeTarget && <p className="text-xs text-text-tertiary">{t.todos.exportTargetHint}</p>}
        </div>
      )}
      {todo.referenceType && (
        <p className="text-xs text-text-tertiary">
          {t.todos.internalReference}: {todo.referenceType}
          {todo.referenceId ? ` / ${todo.referenceId}` : ''}
        </p>
      )}
    </div>
  );
}
