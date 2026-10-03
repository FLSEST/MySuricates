import { Modal, Button, Input, Select, TagInput } from '../ui';
import { useT } from '../../i18n';
import { useModuleEnabled } from '../../hooks/useModules';
import { TodoPlanningPanel } from '../goal/TodoPlanningPanel';
import { useTodoPlanningForm } from '../goal/useTodoPlanningForm';
import { TodoExportSection } from './TodoExportSection';
import { TodoMetadata } from './TodoMetadata';
import type { Todo, TodoStatus, TodoPriority, UpdateTodoRequest, Project, Workflow, IntegrationProviderInfo } from '../../types';

const STATUSES: TodoStatus[] = ['OPEN', 'IN_PROGRESS', 'DONE', 'BACKLOG', 'ARCHIVED'];
const PRIORITIES: TodoPriority[] = ['LOW', 'MEDIUM', 'HIGH'];

/**
 * Was nach dem gespeicherten TODO (PUT) noch zu speichern ist – die Einordnung (bbee09e6). Der Aufrufer
 * schließt den Dialog erst, wenn es erfüllt ist; weist es ab, bleibt er offen.
 */
export type AfterTodoSave = () => Promise<void>;

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
  /** PUT des TODOs, dann `afterSave` abwarten, dann schließen. */
  onSave: (afterSave: AfterTodoSave) => void;
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
  const goalsModule = useModuleEnabled('goals');
  const integrationsModule = useModuleEnabled('integrations');
  // Einordnung gehört zum Modul „Ziele“ (c60bf914) – aus, gibt es sie nicht
  const planning = useTodoPlanningForm(goalsModule ? editTodo : null);

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
            onClick={() => onSave(planning.save)}
            loading={isLoading || planning.saving}
            disabled={!editForm.title?.trim() || planning.conflict}
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

        {editTodo && goalsModule && <TodoPlanningPanel todo={editTodo} planning={planning} />}

        {editTodo && integrationsModule && (
          <TodoExportSection
            todo={editTodo}
            projectId={editForm.projectId}
            projects={projects}
            integrations={integrations}
            onExport={onExport}
            exportLoading={exportLoading}
          />
        )}

        {editTodo && <TodoMetadata todo={editTodo} />}
      </div>
    </Modal>
  );
}
