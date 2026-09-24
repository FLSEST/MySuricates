import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { TodoEditModal } from '../todo/TodoEditModal';
import { useExportTodo, useTodo, useUpdateTodo } from '../../hooks/useTodos';
import { useProjects } from '../../hooks/useProjects';
import { useWorkflows } from '../../hooks/useWorkflows';
import { useIntegrations } from '../../hooks/useIntegrations';
import { goalKeys } from '../../hooks/useGoals';
import type { Todo, UpdateTodoRequest } from '../../types';

function formOf(todo: Todo): UpdateTodoRequest {
  return {
    title: todo.title,
    description: todo.description ?? '',
    status: todo.status,
    priority: todo.priority,
    projectId: todo.projectId ?? '',
    linkedWorkflowId: todo.linkedWorkflowId ?? '',
    tags: todo.tags,
    dueDate: todo.dueDate ?? '',
  };
}

/**
 * Bearbeiten-Dialog eines TODOs direkt auf der Zielseite (TODO c6ea8f5c) – derselbe
 * `TodoEditModal` wie auf der TODO-Seite. Lädt das TODO samt Beschreibung erst beim Öffnen; die
 * Zielansicht selbst kennt nur schlanke Zeilen.
 */
export function GoalTodoEditor({ todoId, onClose }: { todoId: string; onClose: () => void }) {
  const qc = useQueryClient();
  const { data: todo } = useTodo(todoId);
  const { data: projects = [] } = useProjects();
  const { data: workflows = [] } = useWorkflows();
  const { data: integrations = [] } = useIntegrations();
  const updateTodo = useUpdateTodo();
  const exportTodo = useExportTodo();
  // Formular erst füllen, wenn das TODO da ist – und nur einmal je TODO
  const [edit, setEdit] = useState<{ id: string; form: UpdateTodoRequest; tags: string[] } | null>(null);
  if (todo && edit?.id !== todo.id) setEdit({ id: todo.id, form: formOf(todo), tags: [...todo.tags] });

  const save = () => {
    if (!todo || !edit) return;
    updateTodo.mutate({ id: todo.id, data: { ...edit.form, tags: edit.tags } }, {
      onSuccess: () => {
        qc.invalidateQueries({ queryKey: goalKeys.all });
        onClose();
      },
    });
  };

  return (
    <TodoEditModal
      editTodo={todo && edit ? todo : null}
      editForm={edit?.form ?? {}}
      editTagsList={edit?.tags ?? []}
      projects={projects}
      workflows={workflows}
      allTags={edit?.tags ?? []}
      isLoading={updateTodo.isPending}
      onClose={onClose}
      onFormChange={(form) => setEdit((current) => (current ? { ...current, form } : current))}
      onTagsChange={(tags) => setEdit((current) => (current ? { ...current, tags } : current))}
      onSave={save}
      integrations={integrations}
      exportLoading={exportTodo.isPending}
      onExport={(provider, target) => exportTodo.mutate({ id: todoId, provider, target })}
    />
  );
}
