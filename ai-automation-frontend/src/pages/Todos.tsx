import { useState, useRef, useEffect, useMemo } from 'react';
import { usePersistedState } from '../hooks/usePersistedState';
import {
  CheckSquare, Plus, Search, Trash2, Pencil, Bot, User, GitBranch,
  X, List, LayoutGrid, Circle, Clock, RefreshCw,
} from 'lucide-react';
import { Card, Button, Spinner, Badge, TagInput } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { TodoEditModal } from '../components/shared/TodoEditModal';
import {
  useTodos, useTodoCount, useCreateTodo, useUpdateTodo, useUpdateTodoStatus, useDeleteTodo,
} from '../hooks/useTodos';
import { useProjects } from '../hooks/useProjects';
import { useT } from '../i18n';
import type { Todo, TodoStatus, TodoPriority, TodoSource, CreateTodoRequest, UpdateTodoRequest } from '../types';

const STATUSES: TodoStatus[] = ['OPEN', 'IN_PROGRESS', 'DONE', 'BACKLOG'];
const PRIORITIES: TodoPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const SOURCES: TodoSource[] = ['USER', 'AI', 'WORKFLOW'];

const MAX_VISIBLE_TAGS = 8;

type ViewMode = 'list' | 'board';

const priorityColor: Record<TodoPriority, string> = {
  CRITICAL: 'bg-red-700 dark:bg-red-600',
  HIGH: 'bg-danger',
  MEDIUM: 'bg-warning',
  LOW: 'bg-text-tertiary',
};

const sourceIcon = (s: TodoSource) => {
  switch (s) {
    case 'USER': return <User className="w-3 h-3" />;
    case 'AI': return <Bot className="w-3 h-3" />;
    case 'WORKFLOW': return <GitBranch className="w-3 h-3" />;
  }
};

function formatRelative(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}

/* ─── Inline Status Buttons ─────────────────────────────── */
function StatusDots({ current, onChange }: { current: TodoStatus; onChange: (s: TodoStatus) => void }) {
  return (
    <div className="flex items-center gap-1 shrink-0">
      {/* OPEN */}
      <button
        onClick={(e) => { e.stopPropagation(); onChange('OPEN'); }}
        className={`w-5 h-5 rounded-full border-2 flex items-center justify-center transition-colors ${
          current === 'OPEN'
            ? 'border-text-tertiary bg-surface-raised'
            : 'border-border hover:border-text-tertiary'
        }`}
        title="Open"
      />
      {/* IN_PROGRESS */}
      <button
        onClick={(e) => { e.stopPropagation(); onChange('IN_PROGRESS'); }}
        className={`w-5 h-5 rounded-full border-2 flex items-center justify-center transition-colors ${
          current === 'IN_PROGRESS'
            ? 'border-warning bg-warning/20'
            : 'border-border hover:border-warning'
        }`}
        title="In Progress"
      >
        {current === 'IN_PROGRESS' && (
          <div className="w-2.5 h-2.5 rounded-full bg-warning" style={{ clipPath: 'inset(0 50% 0 0)' }}>
            <div className="w-full h-full rounded-full bg-warning" />
          </div>
        )}
      </button>
      {/* DONE */}
      <button
        onClick={(e) => { e.stopPropagation(); onChange('DONE'); }}
        className={`w-5 h-5 rounded-full border-2 flex items-center justify-center transition-colors ${
          current === 'DONE'
            ? 'border-success bg-success text-white'
            : 'border-border hover:border-success'
        }`}
        title="Done"
      >
        {current === 'DONE' && (
          <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={3}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
          </svg>
        )}
      </button>
      {/* BACKLOG */}
      <button
        onClick={(e) => { e.stopPropagation(); onChange('BACKLOG'); }}
        className={`w-5 h-5 rounded-full border-2 flex items-center justify-center transition-colors ${
          current === 'BACKLOG'
            ? 'border-text-tertiary/60 bg-text-tertiary/20'
            : 'border-border hover:border-text-tertiary/60'
        }`}
        title="Backlog"
      >
        {current === 'BACKLOG' && (
          <div className="w-2 h-2 rounded-full bg-text-tertiary/60" />
        )}
      </button>
    </div>
  );
}

/* ─── Progress Bar ──────────────────────────────────────── */
function ProgressBar({ open, inProgress, done, backlog }: { open: number; inProgress: number; done: number; backlog: number }) {
  const t = useT();
  const activeTotal = open + inProgress + done;
  if (activeTotal + backlog === 0) return null;

  return (
    <div className="flex items-center gap-4">
      <div className="flex-1 h-2 rounded-full bg-surface overflow-hidden flex">
        {done > 0 && activeTotal > 0 && (
          <div
            className="h-full bg-success transition-all duration-500"
            style={{ width: `${(done / activeTotal) * 100}%` }}
          />
        )}
        {inProgress > 0 && activeTotal > 0 && (
          <div
            className="h-full bg-warning transition-all duration-500"
            style={{ width: `${(inProgress / activeTotal) * 100}%` }}
          />
        )}
        {open > 0 && activeTotal > 0 && (
          <div
            className="h-full bg-text-tertiary/40 transition-all duration-500"
            style={{ width: `${(open / activeTotal) * 100}%` }}
          />
        )}
      </div>
      <div className="flex items-center gap-3 shrink-0 text-xs">
        <span className="flex items-center gap-1 text-text-tertiary">
          <Circle className="w-2 h-2 fill-text-tertiary/40 text-text-tertiary/40" />
          {open} {t.todos.statuses.OPEN.toLowerCase()}
        </span>
        <span className="flex items-center gap-1 text-warning">
          <Circle className="w-2 h-2 fill-warning text-warning" />
          {inProgress} {t.todos.statuses.IN_PROGRESS.toLowerCase()}
        </span>
        <span className="flex items-center gap-1 text-success">
          <Circle className="w-2 h-2 fill-success text-success" />
          {done} {t.todos.statuses.DONE.toLowerCase()}
        </span>
        {backlog > 0 && (
          <span className="flex items-center gap-1 text-text-tertiary/60">
            <Circle className="w-2 h-2 fill-text-tertiary/30 text-text-tertiary/30" />
            {backlog} {t.todos.statuses.BACKLOG.toLowerCase()}
          </span>
        )}
      </div>
    </div>
  );
}

/* ─── Kanban Card ───────────────────────────────────────── */
function KanbanCard({ todo, onEdit, onStatusChange }: {
  todo: Todo;
  onEdit: (todo: Todo) => void;
  onStatusChange: (todo: Todo, status: TodoStatus) => void;
}) {
  return (
    <div
      className="bg-surface-base border border-border rounded-lg p-3 hover:border-border-strong transition-colors cursor-pointer group"
      onClick={() => onEdit(todo)}
    >
      <div className="flex gap-2">
        {/* Priority bar */}
        <div className={`w-1 rounded-full shrink-0 self-stretch ${priorityColor[todo.priority]}`} />
        <div className="flex-1 min-w-0">
          {/* Title */}
          <p className="text-sm font-medium text-text leading-snug">{todo.title}</p>
          {/* Description */}
          {todo.description && (
            <p className="text-xs text-text-tertiary mt-1 line-clamp-2">{todo.description}</p>
          )}
          {/* Bottom row: badges */}
          <div className="flex flex-wrap items-center gap-1.5 mt-2">
            {todo.projectName && (
              <span className="px-1.5 py-0.5 rounded text-[10px] bg-accent/10 text-accent font-medium">
                {todo.projectName}
              </span>
            )}
            {todo.tags.slice(0, 3).map((tag) => (
              <span key={tag} className="px-1.5 py-0.5 rounded text-[10px] bg-surface-raised text-text-tertiary border border-border">
                {tag}
              </span>
            ))}
            {todo.tags.length > 3 && (
              <span className="text-[10px] text-text-tertiary">+{todo.tags.length - 3}</span>
            )}
          </div>
        </div>
      </div>
      {/* Quick status change buttons (on hover) */}
      <div className="flex items-center justify-between mt-2 pt-2 border-t border-border/50">
        <span className="text-[10px] text-text-tertiary inline-flex items-center gap-0.5" title={`Erstellt: ${new Date(todo.createdAt).toLocaleString()}`}>
          <Clock className="w-2.5 h-2.5" />
          {formatRelative(todo.createdAt)}
        </span>
        {todo.updatedAt && Math.abs(new Date(todo.updatedAt).getTime() - new Date(todo.createdAt).getTime()) > 1000 && (
          <span className="text-[10px] text-text-tertiary inline-flex items-center gap-0.5" title={`Aktualisiert: ${new Date(todo.updatedAt).toLocaleString()}`}>
            <RefreshCw className="w-2.5 h-2.5" />
            {formatRelative(todo.updatedAt)}
          </span>
        )}
        <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
          {todo.status !== 'OPEN' && (
            <button
              onClick={(e) => { e.stopPropagation(); onStatusChange(todo, 'OPEN'); }}
              className="px-1.5 py-0.5 rounded text-[10px] text-text-tertiary hover:bg-surface-raised transition-colors"
            >
              Open
            </button>
          )}
          {todo.status !== 'IN_PROGRESS' && (
            <button
              onClick={(e) => { e.stopPropagation(); onStatusChange(todo, 'IN_PROGRESS'); }}
              className="px-1.5 py-0.5 rounded text-[10px] text-warning hover:bg-warning/10 transition-colors"
            >
              WIP
            </button>
          )}
          {todo.status !== 'DONE' && (
            <button
              onClick={(e) => { e.stopPropagation(); onStatusChange(todo, 'DONE'); }}
              className="px-1.5 py-0.5 rounded text-[10px] text-success hover:bg-success/10 transition-colors"
            >
              Done
            </button>
          )}
          {todo.status !== 'BACKLOG' && (
            <button
              onClick={(e) => { e.stopPropagation(); onStatusChange(todo, 'BACKLOG'); }}
              className="px-1.5 py-0.5 rounded text-[10px] text-text-tertiary/60 hover:bg-text-tertiary/10 transition-colors"
            >
              Backlog
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

/* ─── Kanban Board ──────────────────────────────────────── */
function KanbanBoard({ todos, onEdit, onStatusChange }: {
  todos: Todo[];
  onEdit: (todo: Todo) => void;
  onStatusChange: (todo: Todo, status: TodoStatus) => void;
}) {
  const t = useT();

  const columns: { status: TodoStatus; label: string; color: string; borderColor: string }[] = [
    { status: 'OPEN', label: t.todos.statuses.OPEN, color: 'text-text-tertiary', borderColor: 'border-text-tertiary/30' },
    { status: 'IN_PROGRESS', label: t.todos.statuses.IN_PROGRESS, color: 'text-warning', borderColor: 'border-warning/30' },
    { status: 'DONE', label: t.todos.statuses.DONE, color: 'text-success', borderColor: 'border-success/30' },
    { status: 'BACKLOG', label: t.todos.statuses.BACKLOG, color: 'text-text-tertiary/60', borderColor: 'border-text-tertiary/20' },
  ];

  const grouped = useMemo(() => {
    const map: Record<TodoStatus, Todo[]> = { OPEN: [], IN_PROGRESS: [], DONE: [], BACKLOG: [] };
    todos.forEach((todo) => map[todo.status].push(todo));
    return map;
  }, [todos]);

  return (
    <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
      {columns.map(({ status, label, color, borderColor }) => (
        <div key={status} className={`rounded-xl border-2 ${borderColor} bg-surface-raised/30 p-3`}>
          {/* Column header */}
          <div className="flex items-center justify-between mb-3">
            <h3 className={`text-sm font-semibold ${color}`}>{label}</h3>
            <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${color} bg-surface-raised`}>
              {grouped[status].length}
            </span>
          </div>
          {/* Cards */}
          <div className="space-y-2">
            {grouped[status].length === 0 ? (
              <div className="text-center py-6 text-xs text-text-tertiary">—</div>
            ) : (
              grouped[status].map((todo) => (
                <KanbanCard
                  key={todo.id}
                  todo={todo}
                  onEdit={onEdit}
                  onStatusChange={onStatusChange}
                />
              ))
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

/* ─── List Item ─────────────────────────────────────────── */
function TodoListItem({ todo, onEdit, onStatusChange, onDelete }: {
  todo: Todo;
  onEdit: (todo: Todo) => void;
  onStatusChange: (todo: Todo, status: TodoStatus) => void;
  onDelete: (id: string) => void;
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
            <p className="text-xs text-text-tertiary line-clamp-2 flex-1 min-w-[120px]">{todo.description}</p>
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
}

/* ─── Main Todos Page ───────────────────────────────────── */
export function Todos() {
  const t = useT();
  // Filter state (declared before useTodos so we can pass filters to the API)
  const [statusFilter, setStatusFilter] = usePersistedState<TodoStatus | 'ALL'>('todos-status-filter', 'ALL');
  const [sourceFilter, setSourceFilter] = usePersistedState<TodoSource | 'ALL'>('todos-source-filter', 'ALL');
  const [projectFilter, setProjectFilter] = usePersistedState('todos-project-filter', '');
  const [search, setSearch] = usePersistedState('todos-search', '');
  const [tagFilter, setTagFilter] = usePersistedState('todos-tag-filter', '');
  const [showAllTags, setShowAllTags] = useState(false);

  // Build server-side filter params
  const apiParams = useMemo(() => {
    const params: Record<string, string> = {};
    if (statusFilter !== 'ALL') params.status = statusFilter;
    if (sourceFilter !== 'ALL') params.source = sourceFilter;
    if (projectFilter) params.projectId = projectFilter;
    if (tagFilter) params.tag = tagFilter;
    if (search) params.q = search;
    return params;
  }, [statusFilter, sourceFilter, projectFilter, tagFilter, search]);

  const { data: todosPage, isLoading, error, refetch } = useTodos(apiParams, 0, 500);
  const todos = todosPage?.content;
  const { data: counts } = useTodoCount();
  const { data: projects } = useProjects();
  const createTodo = useCreateTodo();
  const updateTodo = useUpdateTodo();
  const updateStatus = useUpdateTodoStatus();
  const deleteTodo = useDeleteTodo();

  // Quick-add state
  const [quickTitle, setQuickTitle] = useState('');
  const [quickDescription, setQuickDescription] = useState('');
  const [quickProjectId, setQuickProjectId] = useState('');
  const [quickPriority, setQuickPriority] = useState<TodoPriority>('MEDIUM');
  const [quickTags, setQuickTags] = useState<string[]>([]);
  const quickInputRef = useRef<HTMLInputElement>(null);

  // View mode
  const [viewMode, setViewMode] = usePersistedState<ViewMode>('todos-view-mode', 'list');

  // Edit modal state
  const [editTodo, setEditTodo] = useState<Todo | null>(null);
  const [editForm, setEditForm] = useState<UpdateTodoRequest>({});
  const [editTagsList, setEditTagsList] = useState<string[]>([]);

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

  const handleQuickAdd = () => {
    if (!quickTitle.trim()) return;
    const req: CreateTodoRequest = {
      title: quickTitle.trim(),
      description: quickDescription.trim() || undefined,
      priority: quickPriority,
      projectId: quickProjectId || undefined,
      tags: quickTags.length > 0 ? quickTags : undefined,
    };
    createTodo.mutate(req, {
      onSuccess: () => {
        setQuickTitle('');
        setQuickDescription('');
        setQuickPriority('MEDIUM');
        setQuickTags([]);
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
      tags: todo.tags,
    });
    setEditTagsList([...todo.tags]);
  };

  const handleEditSave = () => {
    if (!editTodo) return;
    const data: UpdateTodoRequest = {
      ...editForm,
      tags: editTagsList,
    };
    updateTodo.mutate({ id: editTodo.id, data }, {
      onSuccess: () => setEditTodo(null),
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
        <ProgressBar open={openCount} inProgress={inProgressCount} done={doneCount} backlog={backlogCount} />
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
      {filtered.length === 0 ? (
        <EmptyState
          icon={<CheckSquare className="w-8 h-8 text-text-tertiary" />}
          title={search || statusFilter !== 'ALL' || tagFilter ? t.common.noResults : t.todos.title}
          description={search || statusFilter !== 'ALL' || tagFilter ? undefined : t.todos.noTodos}
        />
      ) : viewMode === 'board' ? (
        <KanbanBoard
          todos={filtered}
          onEdit={openEdit}
          onStatusChange={handleStatusChange}
        />
      ) : (
        <Card padding={false}>
          <div className="divide-y divide-border">
            {filtered.map((todo) => (
              <TodoListItem
                key={todo.id}
                todo={todo}
                onEdit={openEdit}
                onStatusChange={handleStatusChange}
                onDelete={(id) => deleteTodo.mutate(id)}
              />
            ))}
          </div>
        </Card>
      )}

      <TodoEditModal
        editTodo={editTodo}
        editForm={editForm}
        editTagsList={editTagsList}
        projects={projects ?? []}
        allTags={allTags}
        isLoading={updateTodo.isPending}
        onClose={() => setEditTodo(null)}
        onFormChange={setEditForm}
        onTagsChange={setEditTagsList}
        onSave={handleEditSave}
      />
    </div>
  );
}
