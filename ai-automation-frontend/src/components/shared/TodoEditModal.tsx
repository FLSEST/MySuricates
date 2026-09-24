import { useSyncedState } from '../../hooks/useSyncedState';
import { Link } from 'react-router-dom';
import { ExternalLink } from 'lucide-react';
import { Modal, Button, Input, Select, TagInput } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { formatTimestamp } from '../../utils/localeFormat';
import { useTaskTargets } from '../../hooks/useIntegrations';
import { useModuleEnabled } from '../../hooks/useModules';
import { ProviderIcon } from './ProviderIcon';
import { TodoPlanningPanel } from '../goal/TodoPlanningPanel';
import type { Todo, TodoStatus, TodoPriority, UpdateTodoRequest, Project, Workflow, IntegrationProviderInfo } from '../../types';

const STATUSES: TodoStatus[] = ['OPEN', 'IN_PROGRESS', 'DONE', 'BACKLOG', 'ARCHIVED'];
const PRIORITIES: TodoPriority[] = ['LOW', 'MEDIUM', 'HIGH'];

interface TodoEditModalProps {
  editTodo: Todo | null;
  editForm: UpdateTodoRequest;
  editTagsList: string[];
  projects: Project[];
  workflows: Workflow[];
  allTags: string[];
  isLoading: boolean;
  onClose: () => void;
  onFormChange: (form: UpdateTodoRequest) => void;
  onTagsChange: (tags: string[]) => void;
  onSave: () => void;
  integrations: IntegrationProviderInfo[];
  onExport: (provider: string, target: string) => void;
  exportLoading?: boolean;
}

export function TodoEditModal({
  editTodo,
  editForm,
  editTagsList,
  projects,
  workflows,
  allTags,
  isLoading,
  onClose,
  onFormChange,
  onTagsChange,
  onSave,
  integrations,
  onExport,
  exportLoading,
}: TodoEditModalProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const integrationsModule = useModuleEnabled('integrations');
  // Export fields start empty for every todo that is opened (reset when the todo id changes)
  const [exportProvider, setExportProvider] = useSyncedState(editTodo?.id, () => '');
  const [exportTarget, setExportTarget] = useSyncedState(editTodo?.id, () => '');

  const taskSinks = integrations.filter((p) => p.supportsTaskExport && p.status === 'CONNECTED');
  const exportProject = projects.find((p) => p.id === (editForm.projectId ?? editTodo?.projectId ?? ''));
  const exportCfg = (exportProject?.config?.taskExport ?? {}) as { provider?: string; target?: string };
  const activeProvider = exportProvider || exportCfg.provider || taskSinks[0]?.providerId || '';
  const activeTarget = (exportTarget.trim() || exportCfg.target || '').trim();
  const { data: taskTargets } = useTaskTargets(
    activeProvider,
    !!editTodo && !editTodo.exportedProvider && taskSinks.length > 0,
  );
  const activeProviderName = taskSinks.find((p) => p.providerId === activeProvider)?.displayName ?? activeProvider;
  const activeProviderIcon = taskSinks.find((p) => p.providerId === activeProvider)?.icon;
  const exportedProviderName = integrations.find((p) => p.providerId === editTodo?.exportedProvider)?.displayName
    ?? editTodo?.exportedProvider ?? '';
  const exportedProviderIcon = integrations.find((p) => p.providerId === editTodo?.exportedProvider)?.icon;

  return (
    <Modal
      open={!!editTodo}
      onClose={onClose}
      title={t.todos.editTodo}
      size="lg"
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            {t.common.cancel}
          </Button>
          <Button
            onClick={onSave}
            loading={isLoading}
            disabled={!editForm.title?.trim()}
          >
            {t.common.save}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Input
          label={t.todos.titleLabel}
          value={editForm.title ?? ''}
          onChange={(e) => onFormChange({ ...editForm, title: e.target.value })}
        />

        <div className="space-y-1.5">
          <label className="block text-sm font-medium text-text-secondary">{t.todos.descriptionLabel}</label>
          <textarea
            className="input-base w-full min-h-[80px] resize-y"
            value={editForm.description ?? ''}
            onChange={(e) => onFormChange({ ...editForm, description: e.target.value })}
          />
        </div>

        {/* Status buttons */}
        <div className="space-y-1.5">
          <label className="block text-sm font-medium text-text-secondary">{t.common.status}</label>
          <div className="flex gap-2">
            {STATUSES.map((s) => (
              <button
                key={s}
                onClick={() => onFormChange({ ...editForm, status: s })}
                className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors border ${
                  editForm.status === s
                    ? 'bg-accent text-text-inverse border-accent'
                    : 'bg-surface-raised text-text-secondary border-border hover:border-border-strong'
                }`}
              >
                {t.todos.statuses[s]}
              </button>
            ))}
          </div>
        </div>

        {/* Priority buttons */}
        <div className="space-y-1.5">
          <label className="block text-sm font-medium text-text-secondary">{t.todos.priority}</label>
          <div className="flex gap-2">
            {PRIORITIES.map((p) => (
              <button
                key={p}
                onClick={() => onFormChange({ ...editForm, priority: p })}
                className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors border ${
                  editForm.priority === p
                    ? 'bg-accent text-text-inverse border-accent'
                    : 'bg-surface-raised text-text-secondary border-border hover:border-border-strong'
                }`}
              >
                {t.todos.priorities[p]}
              </button>
            ))}
          </div>
        </div>

        {/* Due Date */}
        <div className="space-y-1.5">
          <label className="block text-sm font-medium text-text-secondary">{t.todos.dueDate}</label>
          <input
            type="date"
            className="input-base w-full"
            value={editForm.dueDate ? editForm.dueDate.split('T')[0] : ''}
            onChange={(e) => onFormChange({
              ...editForm,
              dueDate: e.target.value ? new Date(e.target.value + 'T23:59:59').toISOString() : '',
            })}
          />
        </div>

        {/* Project */}
        <Select
          label={t.common.project}
          value={editForm.projectId ?? ''}
          onChange={(e) => onFormChange({ ...editForm, projectId: e.target.value })}
          options={[
            { value: '', label: t.common.selectProject },
            ...projects.map((p) => ({ value: p.id, label: p.name })),
          ]}
        />

        <Select
          label={t.todos.linkedWorkflow}
          value={editForm.linkedWorkflowId ?? ''}
          onChange={(e) => onFormChange({ ...editForm, linkedWorkflowId: e.target.value })}
          options={[
            { value: '', label: t.todos.noLinkedWorkflow },
            ...workflows.map((w) => ({ value: w.id, label: w.name })),
          ]}
        />

        {/* Tags */}
        <TagInput
          label={t.todos.tags}
          tags={editTagsList}
          onChange={onTagsChange}
          suggestions={allTags}
          placeholder="bug, performance, ux"
        />

        {editTodo && <TodoPlanningPanel todo={editTodo} />}

        {/* Übertragung / Verknüpfungen */}
        {editTodo && integrationsModule && (
          <div className="pt-3 border-t border-border space-y-2">
            <label className="block text-sm font-medium text-text-secondary">{t.todos.exportSection}</label>
            {editTodo.exportedProvider ? (
              <p className="text-xs text-text-secondary">
                {t.todos.exportedTo}: <ProviderIcon icon={exportedProviderIcon} className="w-3.5 h-3.5 inline-block align-text-bottom mr-0.5" /><span className="font-medium">{exportedProviderName}</span>{' '}
                {editTodo.exportedUrl ? (
                  <a href={editTodo.exportedUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-0.5 text-accent hover:underline">
                    {editTodo.exportedRef}<ExternalLink className="w-3 h-3" />
                  </a>
                ) : (
                  <span>{editTodo.exportedRef}</span>
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
                      onChange={(e) => setExportProvider(e.target.value)}
                    >
                      {taskSinks.map((s) => (
                        <option key={s.providerId} value={s.providerId}>{s.displayName}</option>
                      ))}
                    </select>
                  ) : (
                    <span className="inline-flex items-center gap-1 text-xs font-medium text-text-secondary shrink-0">
                      <ProviderIcon icon={activeProviderIcon} className="w-3.5 h-3.5" />
                      {activeProviderName}
                    </span>
                  )}
                  {taskTargets && taskTargets.length > 0 ? (
                    <select
                      className="input-base text-sm flex-1"
                      value={activeTarget}
                      onChange={(e) => setExportTarget(e.target.value)}
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
                      value={exportTarget}
                      onChange={(e) => setExportTarget(e.target.value)}
                      placeholder={exportCfg.target || t.todos.exportTargetPlaceholder}
                    />
                  )}
                  <Button size="sm" variant="secondary" loading={exportLoading} disabled={!activeTarget} onClick={() => onExport(activeProvider, activeTarget)}>
                    {t.todos.exportToTracker}
                  </Button>
                </div>
                {!activeTarget && <p className="text-xs text-text-tertiary">{t.todos.exportTargetHint}</p>}
              </div>
            )}
            {editTodo.referenceType && (
              <p className="text-xs text-text-tertiary">
                {t.todos.internalReference}: {editTodo.referenceType}
                {editTodo.referenceId ? ` / ${editTodo.referenceId}` : ''}
              </p>
            )}
          </div>
        )}

        {/* Read-only metadata */}
        {editTodo && (
          <div className="pt-3 border-t border-border space-y-1">
            <p className="text-xs text-text-tertiary">
              {t.todos.source}: {t.todos.sources[editTodo.source]}
              {editTodo.sourceAgent && ` (${editTodo.sourceAgent})`}
            </p>
            {editTodo.sessionId && (
              <p className="text-xs text-text-tertiary">
                {t.todos.sessionId}: {editTodo.sessionId}
              </p>
            )}
            {editTodo.workflowRunId && (
              <div className="flex items-center gap-2 text-xs text-text-tertiary">
                <span>Run:</span>
                <Link
                  to={`/runs/${editTodo.workflowRunId}`}
                  className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] bg-accent/10 text-accent font-medium hover:bg-accent/20 transition-colors"
                  title={t.todos.viewRun}
                >
                  <ExternalLink className="w-2.5 h-2.5" />
                  Run
                </Link>
              </div>
            )}
            {editTodo.lastFixRunId && (
              <div className="flex items-center gap-2 text-xs text-text-tertiary">
                <span>{t.todos.viewFixRun}:</span>
                <Link
                  to={`/runs/${editTodo.lastFixRunId}`}
                  className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] bg-warning/10 text-warning font-medium hover:bg-warning/20 transition-colors"
                  title={t.todos.viewFixRun}
                >
                  <ExternalLink className="w-2.5 h-2.5" />
                  Fix Run
                </Link>
              </div>
            )}
            {editTodo.linkedWorkflowId && (
              <div className="flex items-center gap-2 text-xs text-text-tertiary">
                <span>{t.todos.linkedWorkflow}:</span>
                <Link
                  to={`/workflows/${editTodo.linkedWorkflowId}`}
                  className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] bg-accent/10 text-accent font-medium hover:bg-accent/20 transition-colors"
                  title={editTodo.linkedWorkflowName ?? t.common.workflow}
                >
                  <ExternalLink className="w-2.5 h-2.5" />
                  {editTodo.linkedWorkflowName ?? t.common.workflow}
                </Link>
              </div>
            )}
            <p className="text-xs text-text-tertiary">
              {t.common.created}: {formatTimestamp(editTodo.createdAt, locale)}
            </p>
            <p className="text-xs text-text-tertiary">
              {t.common.updated}: {formatTimestamp(editTodo.updatedAt, locale)}
            </p>
          </div>
        )}
      </div>
    </Modal>
  );
}
