import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CheckSquare } from 'lucide-react';
import { Card, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { Hint } from '../components/hints/Hint';
import { ErrorState } from '../components/shared/ErrorState';
import { Pagination } from '../components/shared/Pagination';
import { TodoEditModal } from '../components/todo/TodoEditModal';
import { TodoTriggerSection } from '../components/todo/TodoTriggerSection';
import { TodoProgressBar } from '../components/todo/TodoProgressBar';
import { TodoKanbanView } from '../components/todo/TodoKanbanView';
import { TodoListItem } from '../components/todo/TodoListView';
import { TodoQuickAdd } from '../components/todo/TodoQuickAdd';
import { TodoFilterBar } from '../components/todo/TodoFilterBar';
import { TodoTagChips } from '../components/todo/TodoTagChips';
import { useTodoCount, useUpdateTodoStatus, useDeleteTodo, useResolveTodo } from '../hooks/useTodos';
import { useTodoFilters } from '../hooks/useTodoFilters';
import { useTodoQuickAdd } from '../hooks/useTodoQuickAdd';
import { useTodoBoard } from '../hooks/useTodoBoard';
import { useTodoEditor } from '../hooks/useTodoEditor';
import { useProjects } from '../hooks/useProjects';
import { useIntegrations } from '../hooks/useIntegrations';
import { useWorkflows } from '../hooks/useWorkflows';
import { useT } from '../i18n';
import type { Todo, TodoStatus } from '../types';

/* ─── Main Todos Page ───────────────────────────────────── */
export function Todos() {
  const t = useT();
  const navigate = useNavigate();
  // Filter state (declared before the queries so we can pass filters to the API)
  const filters = useTodoFilters();
  const { statusFilter, tagFilter, setTagFilter, viewMode, page, setPage } = filters;
  const [showAllTags, setShowAllTags] = useState(false);

  const {
    isBoard, boardColumns, todosPage, todos, isLoading, error, refetch, boardStillLoading,
  } = useTodoBoard({ viewMode, statusFilter, filterParams: filters.filterParams, apiParams: filters.apiParams, page });
  const { data: counts } = useTodoCount();
  const { data: projects } = useProjects();
  const { data: workflows = [] } = useWorkflows();
  const updateStatus = useUpdateTodoStatus();
  const deleteTodo = useDeleteTodo();
  const resolveTodo = useResolveTodo();
  const { data: integrations } = useIntegrations();

  // Quick-add state (lives in the page: survives the loading spinner, 'n' works from mount)
  const { form: quickAdd, inputRef: quickInputRef } = useTodoQuickAdd();

  // Edit modal and deep link ?todoId= (opens the todo, highlights its row)
  const editor = useTodoEditor(filters);

  // Server-side filtering — todos already filtered by API params
  const filtered = todos ?? [];

  // Tags aus den aktuellen Ergebnissen (ein zweiter API-Aufruf ohne Tag-Filter lohnt nicht).
  // Ohne useMemo: todos entsteht in jedem Render neu, das Memo hielte nie (react-hooks 7.1).
  const allTags = Array.from(new Set((todos ?? []).flatMap((todo) => todo.tags))).sort();

  // Counts from dedicated count API (accurate across all pages)
  const openCount = counts?.open ?? 0;
  const inProgressCount = counts?.inProgress ?? 0;
  const doneCount = counts?.done ?? 0;
  const backlogCount = counts?.backlog ?? 0;
  /** Distinguishes "nothing matches the filter" from "no todos exist yet". */
  const hasAnyTodos = openCount + inProgressCount + doneCount + backlogCount + (counts?.archived ?? 0) > 0;

  const handleStatusChange = (todo: Todo, status: TodoStatus) => {
    if (todo.status === status) return;
    updateStatus.mutate({ id: todo.id, status });
  };

  const handleResolve = (todo: Todo) => {
    resolveTodo.mutate(todo.id, {
      onSuccess: (run) => {
        navigate(`/workflows/${run.workflowId}/run/${run.id}`);
      },
    });
  };

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }

  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <div className="space-y-4 animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-text">{t.todos.title}</h1>
          <p className="text-sm text-text-secondary mt-1">{t.todos.subtitle}</p>
        </div>
      </div>

      <Hint id="todos-sources" />

      {/* Progress Bar */}
      {(todos && todos.length > 0) && (
        <TodoProgressBar open={openCount} inProgress={inProgressCount} done={doneCount} backlog={backlogCount} />
      )}

      {/* Quick-Add Bar */}
      <TodoQuickAdd form={quickAdd} inputRef={quickInputRef} projects={projects} allTags={allTags} />

      {/* Filter Bar */}
      <TodoFilterBar filters={filters} projects={projects} />

      {/* Tag chips (collapsible) */}
      <TodoTagChips
        tags={allTags}
        tagFilter={tagFilter}
        onTagFilterChange={setTagFilter}
        expanded={showAllTags}
        onExpandedChange={setShowAllTags}
      />

      {/* Content: List or Board */}
      {filtered.length === 0 && !boardStillLoading ? (
        <EmptyState
          icon={<CheckSquare className="w-8 h-8 text-text-tertiary" />}
          title={hasAnyTodos ? t.common.noResults : t.todos.title}
          description={hasAnyTodos ? undefined : t.todos.noTodos}
        />
      ) : isBoard ? (
        <TodoKanbanView
          columns={boardColumns}
          onEdit={editor.openEdit}
          onStatusChange={handleStatusChange}
          onResolve={handleResolve}
          resolveLoadingId={resolveTodo.variables ?? null}
          highlightId={editor.highlightId}
        />
      ) : (
        <>
          <Card padding={false}>
            <div className="divide-y divide-border">
              {filtered.map((todo) => (
                <TodoListItem
                  key={todo.id}
                  todo={todo}
                  onEdit={editor.openEdit}
                  onStatusChange={handleStatusChange}
                  onResolve={handleResolve}
                  resolveLoading={resolveTodo.isPending && resolveTodo.variables === todo.id}
                  exportedIcon={integrations?.find((p) => p.providerId === todo.exportedProvider)?.icon}
                  onDelete={(id) => deleteTodo.mutate(id)}
                  highlighted={editor.highlightId === todo.id}
                />
              ))}
            </div>
          </Card>
          {todosPage && (
            <Pagination
              page={page}
              totalPages={todosPage.totalPages}
              totalElements={todosPage.totalElements}
              label={t.todos.title}
              setPage={setPage}
            />
          )}
        </>
      )}

      <div className="mt-8">
        <TodoTriggerSection />
      </div>

      <TodoEditModal
        editTodo={editor.editTodo}
        editForm={editor.editForm}
        editTagsList={editor.editTagsList}
        projects={projects ?? []}
        workflows={workflows}
        allTags={allTags}
        isLoading={editor.saving}
        onClose={editor.closeEdit}
        onFormChange={editor.setEditForm}
        onTagsChange={editor.setEditTagsList}
        onSave={editor.save}
        integrations={integrations ?? []}
        exportLoading={editor.exporting}
        onExport={editor.exportTo}
      />
    </div>
  );
}
