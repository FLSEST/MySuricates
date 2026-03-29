import { Modal, Button, Input, Select, TagInput } from '../ui';
import { useT } from '../../i18n';
import type { Todo, TodoStatus, TodoPriority, UpdateTodoRequest, Project } from '../../types';

const STATUSES: TodoStatus[] = ['OPEN', 'IN_PROGRESS', 'DONE', 'BACKLOG'];
const PRIORITIES: TodoPriority[] = ['LOW', 'MEDIUM', 'HIGH'];

interface TodoEditModalProps {
  editTodo: Todo | null;
  editForm: UpdateTodoRequest;
  editTagsList: string[];
  projects: Project[];
  allTags: string[];
  isLoading: boolean;
  onClose: () => void;
  onFormChange: (form: UpdateTodoRequest) => void;
  onTagsChange: (tags: string[]) => void;
  onSave: () => void;
}

export function TodoEditModal({
  editTodo,
  editForm,
  editTagsList,
  projects,
  allTags,
  isLoading,
  onClose,
  onFormChange,
  onTagsChange,
  onSave,
}: TodoEditModalProps) {
  const t = useT();

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

        {/* Tags */}
        <TagInput
          label={t.todos.tags}
          tags={editTagsList}
          onChange={onTagsChange}
          suggestions={allTags}
          placeholder="bug, performance, ux"
        />

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
            <p className="text-xs text-text-tertiary">
              {t.common.created}: {new Date(editTodo.createdAt).toLocaleString()}
            </p>
            <p className="text-xs text-text-tertiary">
              {t.common.updated}: {new Date(editTodo.updatedAt).toLocaleString()}
            </p>
          </div>
        )}
      </div>
    </Modal>
  );
}
