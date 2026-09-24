import { useEffect, useRef, useState } from 'react';
import { useExportTodo, useTodo, useUpdateTodo } from './useTodos';
import { filterShowsStatus } from '../components/todo/todoConstants';
import type { useTodoFilters } from './useTodoFilters';
import type { Todo, UpdateTodoRequest } from '../types';

type TodoFilters = ReturnType<typeof useTodoFilters>;

/**
 * Bearbeiten-Dialog der TODO-Seite samt Deep-Link `?todoId=`: Das verlinkte TODO wird per ID
 * geladen (unabhängig von Filtern und Seite) und einmal geöffnet; Filter, die es verbergen
 * würden, werden gelockert. Seine Zeile bleibt hervorgehoben, bis kurz nach dem Schließen, und
 * wird dann in den sichtbaren Bereich geholt. Dazu Speichern und Export in einen Tracker.
 */
export function useTodoEditor(filters: Pick<TodoFilters,
  'searchParams' | 'statusFilter' | 'setStatusFilter' | 'tagFilter' | 'setTagFilter' | 'setProjectFilter'>) {
  const { searchParams, statusFilter, setStatusFilter, tagFilter, setTagFilter, setProjectFilter } = filters;

  // Deep-link to a single todo: ?todoId=… is resolved by id (not by searching the
  // paginated list), so it works regardless of persisted filters and page.
  const linkedTodoId = searchParams.get('todoId') ?? '';
  const { data: linkedTodo } = useTodo(linkedTodoId);
  const updateTodo = useUpdateTodo();
  const exportTodo = useExportTodo();

  // Edit modal state
  const [editTodo, setEditTodo] = useState<Todo | null>(null);
  const [editForm, setEditForm] = useState<UpdateTodoRequest>({});
  const [editTagsList, setEditTagsList] = useState<string[]>([]);

  // Deep-link highlight: marks the row the user came from until shortly after the modal closes
  const [highlightId, setHighlightId] = useState<string | null>(null);
  const handledDeepLink = useRef<string | null>(null);
  const highlightTimer = useRef<number | null>(null);
  useEffect(() => () => { if (highlightTimer.current) window.clearTimeout(highlightTimer.current); }, []);

  const openEdit = (todo: Todo) => {
    setEditTodo(todo);
    setEditForm({
      title: todo.title,
      description: todo.description ?? '',
      status: todo.status,
      priority: todo.priority,
      projectId: todo.projectId ?? '',
      linkedWorkflowId: todo.linkedWorkflowId ?? '',
      tags: todo.tags,
      dueDate: todo.dueDate ?? '',
    });
    setEditTagsList([...todo.tags]);
  };

  const closeEdit = () => {
    const closedId = editTodo?.id;
    setEditTodo(null);
    // Coming back from a deep link: reveal the row the modal was covering
    if (closedId && closedId === highlightId) {
      document.getElementById(`todo-${closedId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      if (highlightTimer.current) window.clearTimeout(highlightTimer.current);
      highlightTimer.current = window.setTimeout(() => setHighlightId(null), 2500);
    }
  };

  // Open the deep-linked todo as soon as it is loaded. The ref guard keeps the modal
  // closed once the user dismissed it — the param stays in the URL.
  useEffect(() => {
    if (!linkedTodo || handledDeepLink.current === linkedTodo.id) return;
    handledDeepLink.current = linkedTodo.id;
    // Preselect the project unless the link already carried it
    if (!searchParams.get('projectId') && linkedTodo.projectId) setProjectFilter(linkedTodo.projectId);
    // Release persisted filters that would hide exactly this todo
    if (!filterShowsStatus(statusFilter, linkedTodo.status)) setStatusFilter('ALL');
    if (tagFilter && !linkedTodo.tags.includes(tagFilter)) setTagFilter('');
    openEdit(linkedTodo);
    setHighlightId(linkedTodo.id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [linkedTodo]);

  const save = () => {
    if (!editTodo) return;
    const data: UpdateTodoRequest = {
      ...editForm,
      tags: editTagsList,
    };
    updateTodo.mutate({ id: editTodo.id, data }, {
      onSuccess: closeEdit,
    });
  };

  const exportTo = (provider: string, target: string) => {
    if (editTodo) exportTodo.mutate({ id: editTodo.id, provider, target });
  };

  return {
    editTodo,
    editForm,
    setEditForm,
    editTagsList,
    setEditTagsList,
    highlightId,
    openEdit,
    closeEdit,
    save,
    saving: updateTodo.isPending,
    exportTo,
    exporting: exportTodo.isPending,
  };
}
