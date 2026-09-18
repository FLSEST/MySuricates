import { useState, useRef, useEffect, useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { usePersistedState } from '../hooks/usePersistedState';
import {
  CheckSquare, Plus, Search,
  X, List, LayoutGrid,
} from 'lucide-react';
import { Card, Button, Spinner, TagInput } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { Pagination } from '../components/shared/Pagination';
import { TodoEditModal } from '../components/shared/TodoEditModal';
import { TodoTriggerSection } from '../components/todo/TodoTriggerSection';
import {
  STATUSES, PRIORITIES, SOURCES, MAX_VISIBLE_TAGS,
  ACTIVE_STATUSES, KANBAN_COLUMN_STATUSES, filterShowsStatus,
  type ViewMode, type TodoStatusFilter,
} from '../components/todo/todoConstants';
import { TodoProgressBar } from '../components/todo/TodoProgressBar';
import { TodoKanbanView, type TodoKanbanColumn } from '../components/todo/TodoKanbanView';
import { TodoListItem } from '../components/todo/TodoListView';
import {
  useTodos, useTodoColumn, useTodo, useTodoCount, useCreateTodo, useUpdateTodo, useUpdateTodoStatus, useDeleteTodo, useResolveTodo, useExportTodo,
} from '../hooks/useTodos';
import { useProjects } from '../hooks/useProjects';
import { useIntegrations } from '../hooks/useIntegrations';
import { useWorkflows } from '../hooks/useWorkflows';
import { useSettingsStore } from '../stores/settingsStore';
import { useT } from '../i18n';
import type { Todo, TodoStatus, TodoPriority, TodoSource, CreateTodoRequest, UpdateTodoRequest } from '../types';

/* ─── Main Todos Page ───────────────────────────────────── */
export function Todos() {
  const t = useT();
  const navigate = useNavigate();
  // Filter state (declared before useTodos so we can pass filters to the API)
  // Default: what is actually being worked on. With a 90 %-DONE backlog, ALL is a long,
  // useless page — ALL stays selectable, and a persisted choice is never overwritten.
  const [statusFilter, setStatusFilter] = usePersistedState<TodoStatusFilter>('todos-status-filter', 'ACTIVE');
  const [sourceFilter, setSourceFilter] = usePersistedState<TodoSource | 'ALL'>('todos-source-filter', 'ALL');
  const [projectFilter, setProjectFilter] = usePersistedState('todos-project-filter', '');

  // Deep-link from the cockpit: ?projectId=… focuses that project (additive — persisted filter wins when absent)
  const [searchParams] = useSearchParams();
  useEffect(() => {
    const pid = searchParams.get('projectId');
    if (pid) setProjectFilter(pid);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  // Deep-link to a single todo: ?todoId=… is resolved by id (not by searching the
  // paginated list), so it works regardless of persisted filters and page.
  const linkedTodoId = searchParams.get('todoId') ?? '';
  const { data: linkedTodo } = useTodo(linkedTodoId);
  const [search, setSearch] = usePersistedState('todos-search', '');
  const [debouncedSearch, setDebouncedSearch] = useState(search);
  const [tagFilter, setTagFilter] = usePersistedState('todos-tag-filter', '');

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), 300);
    return () => clearTimeout(timer);
  }, [search]);
  const [showAllTags, setShowAllTags] = useState(false);

  // View mode
  const [viewMode, setViewMode] = usePersistedState<ViewMode>('todos-view-mode', 'list');

  // Pagination — sizes are user settings, not constants (see TodoSettingsCard)
  const [page, setPage] = useState(0);
  const listPageSize = useSettingsStore((s) => s.listPageSize);
  const kanbanColumnSize = useSettingsStore((s) => s.kanbanColumnSize);

  // Filters without the status — the board adds the status per column
  const filterParams = useMemo(() => {
    const params: Record<string, string> = {};
    if (sourceFilter !== 'ALL') params.source = sourceFilter;
    if (projectFilter) params.projectId = projectFilter;
    if (tagFilter) params.tag = tagFilter;
    if (debouncedSearch) params.q = debouncedSearch;
    return params;
  }, [sourceFilter, projectFilter, tagFilter, debouncedSearch]);

  // Build server-side filter params for the list view
  const apiParams = useMemo<Record<string, string | string[]>>(() => {
    if (statusFilter === 'ALL') return filterParams;
    if (statusFilter === 'ACTIVE') return { ...filterParams, status: ACTIVE_STATUSES };
    return { ...filterParams, status: statusFilter };
  }, [filterParams, statusFilter]);

  // Reset page on filter or view mode change
  useEffect(() => { setPage(0); }, [apiParams, viewMode]);

  const isBoard = viewMode === 'board';

  // Which columns the board shows — the status filter narrows them. ARCHIVED has no
  // permanent column; it only appears when the archive filter is selected explicitly.
  const boardStatuses = useMemo<TodoStatus[]>(() => {
    if (statusFilter === 'ALL') return KANBAN_COLUMN_STATUSES;
    if (statusFilter === 'ACTIVE') return ACTIVE_STATUSES;
    return [statusFilter];
  }, [statusFilter]);

  // One query per column — never distribute a mixed page across the columns (b12a1085).
  const columnEnabled = (status: TodoStatus) => isBoard && boardStatuses.includes(status);
  const openColumn = useTodoColumn('OPEN', kanbanColumnSize, filterParams, columnEnabled('OPEN'));
  const inProgressColumn = useTodoColumn('IN_PROGRESS', kanbanColumnSize, filterParams, columnEnabled('IN_PROGRESS'));
  const doneColumn = useTodoColumn('DONE', kanbanColumnSize, filterParams, columnEnabled('DONE'));
  const backlogColumn = useTodoColumn('BACKLOG', kanbanColumnSize, filterParams, columnEnabled('BACKLOG'));
  const archivedColumn = useTodoColumn('ARCHIVED', kanbanColumnSize, filterParams, columnEnabled('ARCHIVED'));
  const columnQueries = {
    OPEN: openColumn,
    IN_PROGRESS: inProgressColumn,
    DONE: doneColumn,
    BACKLOG: backlogColumn,
    ARCHIVED: archivedColumn,
  } satisfies Record<TodoStatus, ReturnType<typeof useTodoColumn>>;

  const boardColumns: TodoKanbanColumn[] = boardStatuses.map((status) => ({
    status,
    todos: columnQueries[status].data?.content ?? [],
    total: columnQueries[status].data?.totalElements ?? 0,
    isLoading: columnQueries[status].isLoading,
  }));

  const listQuery = useTodos(apiParams, page, listPageSize, !isBoard);
  const todosPage = listQuery.data;
  const isLoading = isBoard
    ? boardColumns.length > 0 && boardColumns.every((column) => column.isLoading)
    : listQuery.isLoading;
  const error = isBoard
    ? boardStatuses.map((status) => columnQueries[status].error).find(Boolean) ?? null
    : listQuery.error;
  const refetch = () => {
    if (isBoard) boardStatuses.forEach((status) => columnQueries[status].refetch());
    else listQuery.refetch();
  };

  // Todos currently on screen — list rows or the union of all board columns
  const todos = isBoard ? boardColumns.flatMap((column) => column.todos) : todosPage?.content;
  const { data: counts } = useTodoCount();
  const { data: projects } = useProjects();
  const { data: workflows = [] } = useWorkflows();
  const createTodo = useCreateTodo();
  const updateTodo = useUpdateTodo();
  const updateStatus = useUpdateTodoStatus();
  const deleteTodo = useDeleteTodo();
  const resolveTodo = useResolveTodo();
  const exportTodo = useExportTodo();
  const { data: integrations } = useIntegrations();

  // Quick-add state
  const [quickTitle, setQuickTitle] = useState('');
  const [quickDescription, setQuickDescription] = useState('');
  const [quickProjectId, setQuickProjectId] = useState('');
  const [quickPriority, setQuickPriority] = useState<TodoPriority>('MEDIUM');
  const [quickTags, setQuickTags] = useState<string[]>([]);
  const [quickDueDate, setQuickDueDate] = useState('');
  const quickInputRef = useRef<HTMLInputElement>(null);

  // Edit modal state
  const [editTodo, setEditTodo] = useState<Todo | null>(null);
  const [editForm, setEditForm] = useState<UpdateTodoRequest>({});
  const [editTagsList, setEditTagsList] = useState<string[]>([]);

  // Deep-link highlight: marks the row the user came from until shortly after the modal closes
  const [highlightId, setHighlightId] = useState<string | null>(null);
  const handledDeepLink = useRef<string | null>(null);
  const highlightTimer = useRef<number | null>(null);
  useEffect(() => () => { if (highlightTimer.current) window.clearTimeout(highlightTimer.current); }, []);

  // Keyboard shortcut: 'n' focuses quick-add
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'n' && !e.ctrlKey && !e.metaKey && !e.altKey) {
        const tag = (e.target as HTMLElement).tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
        e.preventDefault();
        quickInputRef.current?.focus();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  // Server-side filtering — todos already filtered by API params
  const filtered = todos ?? [];

  // Tags from todos (without tag filter, so all relevant tags stay visible)
  const todosWithoutTagFilter = useMemo(() => {
    const params: Record<string, string> = {};
    if (statusFilter !== 'ALL') params.status = statusFilter;
    if (sourceFilter !== 'ALL') params.source = sourceFilter;
    if (projectFilter) params.projectId = projectFilter;
    if (search) params.q = search;
    // We can't easily make a second API call without tag filter,
    // so derive tags from current results + include current tag
    return todos ?? [];
  }, [todos, statusFilter, sourceFilter, projectFilter, search]);

  const allTags = useMemo(() => {
    const set = new Set<string>();
    todosWithoutTagFilter.forEach((todo) => todo.tags.forEach((tag) => set.add(tag)));
    return Array.from(set).sort();
  }, [todosWithoutTagFilter]);

  // Counts from dedicated count API (accurate across all pages)
  const openCount = counts?.open ?? 0;
  const inProgressCount = counts?.inProgress ?? 0;
  const doneCount = counts?.done ?? 0;
  const backlogCount = counts?.backlog ?? 0;
  /** Distinguishes "nothing matches the filter" from "no todos exist yet". */
  const hasAnyTodos = openCount + inProgressCount + doneCount + backlogCount + (counts?.archived ?? 0) > 0;
  /** A board column still loading must not flash the empty state. */
  const boardStillLoading = isBoard && boardColumns.some((column) => column.isLoading);

  const handleQuickAdd = () => {
    if (!quickTitle.trim()) return;
    const req: CreateTodoRequest = {
      title: quickTitle.trim(),
      description: quickDescription.trim() || undefined,
      priority: quickPriority,
      projectId: quickProjectId || undefined,
      tags: quickTags.length > 0 ? quickTags : undefined,
      dueDate: quickDueDate ? new Date(quickDueDate + 'T23:59:59').toISOString() : undefined,
    };
    createTodo.mutate(req, {
      onSuccess: () => {
        setQuickTitle('');
        setQuickDescription('');
        setQuickPriority('MEDIUM');
        setQuickTags([]);
        setQuickDueDate('');
        quickInputRef.current?.focus();
      },
    });
  };

  const handleStatusChange = (todo: Todo, status: TodoStatus) => {
    if (todo.status === status) return;
    updateStatus.mutate({ id: todo.id, status });
  };

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

  const handleEditSave = () => {
    if (!editTodo) return;
    const data: UpdateTodoRequest = {
      ...editForm,
      tags: editTagsList,
    };
    updateTodo.mutate({ id: editTodo.id, data }, {
      onSuccess: closeEdit,
    });
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

  const visibleTags = showAllTags ? allTags : allTags.slice(0, MAX_VISIBLE_TAGS);
  const hasMoreTags = allTags.length > MAX_VISIBLE_TAGS;

  return (
    <div className="space-y-4 animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-text">{t.todos.title}</h1>
          <p className="text-sm text-text-secondary mt-1">{t.todos.subtitle}</p>
        </div>
      </div>

      {/* Progress Bar */}
      {(todos && todos.length > 0) && (
        <TodoProgressBar open={openCount} inProgress={inProgressCount} done={doneCount} backlog={backlogCount} />
      )}

      {/* Quick-Add Bar */}
      <Card>
        <div className="flex items-center gap-3">
          {/* Title */}
          <input
            ref={quickInputRef}
            type="text"
            className="input-base flex-1 min-w-0"
            placeholder={`${t.todos.create}... (n)`}
            value={quickTitle}
            onChange={(e) => setQuickTitle(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.nativeEvent.isComposing) handleQuickAdd(); }}
          />
          {/* Description */}
          <input
            type="text"
            className="input-base flex-1 min-w-0"
            placeholder={`${t.todos.description}...`}
            value={quickDescription}
            onChange={(e) => setQuickDescription(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.nativeEvent.isComposing) handleQuickAdd(); }}
          />
          <div className="w-48">
            <TagInput
              tags={quickTags}
              onChange={setQuickTags}
              suggestions={allTags}
              placeholder={t.todos.tags}
              compact
            />
          </div>
          <select
            className="input-base w-40"
            value={quickProjectId}
            onChange={(e) => setQuickProjectId(e.target.value)}
          >
            <option value="">{t.common.selectProject}</option>
            {projects?.map((p) => (
              <option key={p.id} value={p.id}>{p.name}</option>
            ))}
          </select>
          <select
            className="input-base w-28"
            value={quickPriority}
            onChange={(e) => setQuickPriority(e.target.value as TodoPriority)}
          >
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>{t.todos.priorities[p]}</option>
            ))}
          </select>
          <input
            type="date"
            className="input-base w-36"
            value={quickDueDate}
            onChange={(e) => setQuickDueDate(e.target.value)}
            title={t.todos.dueDate}
          />
          <Button
            icon={<Plus className="w-4 h-4" />}
            onClick={handleQuickAdd}
            loading={createTodo.isPending}
            disabled={!quickTitle.trim()}
          >
            {t.common.create}
          </Button>
        </div>
      </Card>

      {/* Filter Bar */}
      <div className="flex flex-wrap items-center gap-3">
        {/* Status tabs */}
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => setStatusFilter('ACTIVE')}
            className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
              statusFilter === 'ACTIVE'
                ? 'bg-accent text-text-inverse'
                : 'text-text-secondary hover:text-text hover:bg-surface-raised'
            }`}
          >
            {t.todos.activeStatuses}
          </button>
          <button
            onClick={() => setStatusFilter('ALL')}
            className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
              statusFilter === 'ALL'
                ? 'bg-accent text-text-inverse'
                : 'text-text-secondary hover:text-text hover:bg-surface-raised'
            }`}
          >
            {t.todos.allStatuses}
          </button>
          {STATUSES.map((s) => (
            <button
              key={s}
              onClick={() => setStatusFilter(s)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                statusFilter === s
                  ? 'bg-accent text-text-inverse'
                  : 'text-text-secondary hover:text-text hover:bg-surface-raised'
              }`}
            >
              {t.todos.statuses[s]}
            </button>
          ))}
          {/* Archive tab — separated; ARCHIVED is hidden from the default views */}
          <span className="w-px h-4 bg-border mx-1" aria-hidden />
          <button
            onClick={() => setStatusFilter('ARCHIVED')}
            className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
              statusFilter === 'ARCHIVED'
                ? 'bg-accent text-text-inverse'
                : 'text-text-tertiary hover:text-text hover:bg-surface-raised'
            }`}
          >
            {t.todos.statuses.ARCHIVED}
          </button>
        </div>

        {/* Source filter */}
        <select
          className="input-base text-xs py-1.5 w-32"
          value={sourceFilter}
          onChange={(e) => setSourceFilter(e.target.value as TodoSource | 'ALL')}
        >
          <option value="ALL">{t.todos.allSources}</option>
          {SOURCES.map((s) => (
            <option key={s} value={s}>{t.todos.sources[s]}</option>
          ))}
        </select>

        {/* Project filter */}
        <select
          className="input-base text-xs py-1.5 w-40"
          value={projectFilter}
          onChange={(e) => setProjectFilter(e.target.value)}
        >
          <option value="">{t.todos.allProjects}</option>
          {projects?.map((p) => (
            <option key={p.id} value={p.id}>{p.name}</option>
          ))}
        </select>

        {/* Search */}
        <div className="relative flex-1 min-w-[180px] max-w-xs">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-text-tertiary pointer-events-none" />
          <input
            type="text"
            className="input-base pl-9 text-xs py-1.5 w-full"
            placeholder={t.common.search}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>

        {/* View toggle */}
        <div className="flex items-center gap-1 ml-auto">
          <button
            onClick={() => setViewMode('list')}
            className={`p-1.5 rounded-lg transition-colors ${
              viewMode === 'list' ? 'bg-accent text-text-inverse' : 'text-text-tertiary hover:text-text hover:bg-surface-raised'
            }`}
            title={t.todos.viewList}
          >
            <List className="w-4 h-4" />
          </button>
          <button
            onClick={() => setViewMode('board')}
            className={`p-1.5 rounded-lg transition-colors ${
              viewMode === 'board' ? 'bg-accent text-text-inverse' : 'text-text-tertiary hover:text-text hover:bg-surface-raised'
            }`}
            title={t.todos.viewBoard}
          >
            <LayoutGrid className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Tag chips (collapsible) */}
      {allTags.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5">
          {visibleTags.map((tag) => (
            <button
              key={tag}
              onClick={() => setTagFilter(tagFilter === tag ? '' : tag)}
              className={`px-2.5 py-1 rounded-full text-xs font-medium transition-colors border ${
                tagFilter === tag
                  ? 'bg-accent text-text-inverse border-accent'
                  : 'bg-surface-raised text-text-secondary border-border hover:border-border-strong'
              }`}
            >
              {tag}
            </button>
          ))}
          {hasMoreTags && (
            <button
              onClick={() => setShowAllTags(!showAllTags)}
              className="px-2.5 py-1 rounded-full text-xs font-medium text-accent hover:bg-accent/10 transition-colors"
            >
              {showAllTags ? t.todos.showLessTags : `${t.todos.showMoreTags} (+${allTags.length - MAX_VISIBLE_TAGS})`}
            </button>
          )}
          {tagFilter && (
            <button
              onClick={() => setTagFilter('')}
              className="px-2 py-1 rounded-full text-xs text-text-tertiary hover:text-text transition-colors"
            >
              <X className="w-3 h-3" />
            </button>
          )}
        </div>
      )}

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
          onEdit={openEdit}
          onStatusChange={handleStatusChange}
          onResolve={handleResolve}
          resolveLoadingId={resolveTodo.variables ?? null}
          highlightId={highlightId}
        />
      ) : (
        <>
          <Card padding={false}>
            <div className="divide-y divide-border">
              {filtered.map((todo) => (
                <TodoListItem
                  key={todo.id}
                  todo={todo}
                  onEdit={openEdit}
                  onStatusChange={handleStatusChange}
                  onResolve={handleResolve}
                  resolveLoading={resolveTodo.isPending && resolveTodo.variables === todo.id}
                  exportedIcon={integrations?.find((p) => p.providerId === todo.exportedProvider)?.icon}
                  onDelete={(id) => deleteTodo.mutate(id)}
                  highlighted={highlightId === todo.id}
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
        editTodo={editTodo}
        editForm={editForm}
        editTagsList={editTagsList}
        projects={projects ?? []}
        workflows={workflows}
        allTags={allTags}
        isLoading={updateTodo.isPending}
        onClose={closeEdit}
        onFormChange={setEditForm}
        onTagsChange={setEditTagsList}
        onSave={handleEditSave}
        integrations={integrations ?? []}
        exportLoading={exportTodo.isPending}
        onExport={(provider, target) => { if (editTodo) exportTodo.mutate({ id: editTodo.id, provider, target }); }}
      />
    </div>
  );
}
