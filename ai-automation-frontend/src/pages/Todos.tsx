import { useState, useRef, useEffect, useMemo } from 'react';
import {
  CheckSquare, Plus, Search, Trash2, Pencil, Bot, User, GitBranch,
  ArrowUp, ArrowRight, ArrowDown, X,
} from 'lucide-react';
import { Card, Button, Input, Select, Modal, Spinner, Badge, TagInput } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import {
  useTodos, useCreateTodo, useUpdateTodo, useUpdateTodoStatus, useDeleteTodo,
} from '../hooks/useTodos';
import { useProjects } from '../hooks/useProjects';
import { useT } from '../i18n';
import type { Todo, TodoStatus, TodoPriority, TodoSource, CreateTodoRequest, UpdateTodoRequest } from '../types';

const STATUSES: TodoStatus[] = ['OPEN', 'IN_PROGRESS', 'DONE'];
const PRIORITIES: TodoPriority[] = ['LOW', 'MEDIUM', 'HIGH'];
const SOURCES: TodoSource[] = ['USER', 'AI', 'WORKFLOW'];

const priorityIcon = (p: TodoPriority) => {
  switch (p) {
    case 'HIGH': return <ArrowUp className="w-3.5 h-3.5 text-danger" />;
    case 'MEDIUM': return <ArrowRight className="w-3.5 h-3.5 text-warning" />;
    case 'LOW': return <ArrowDown className="w-3.5 h-3.5 text-text-tertiary" />;
  }
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

export function Todos() {
  const t = useT();
  const { data: todos, isLoading, error, refetch } = useTodos();
  const { data: projects } = useProjects();
  const createTodo = useCreateTodo();
  const updateTodo = useUpdateTodo();
  const updateStatus = useUpdateTodoStatus();
  const deleteTodo = useDeleteTodo();

  // Quick-add state
  const [quickTitle, setQuickTitle] = useState('');
  const [quickProjectId, setQuickProjectId] = useState('');
  const [quickPriority, setQuickPriority] = useState<TodoPriority>('MEDIUM');
  const [quickTags, setQuickTags] = useState<string[]>([]);
  const quickInputRef = useRef<HTMLInputElement>(null);

  // Filter state
  const [statusFilter, setStatusFilter] = useState<TodoStatus | 'ALL'>('ALL');
  const [sourceFilter, setSourceFilter] = useState<TodoSource | 'ALL'>('ALL');
  const [projectFilter, setProjectFilter] = useState('');
  const [search, setSearch] = useState('');
  const [tagFilter, setTagFilter] = useState('');

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

  // Client-side filtering
  const filtered = useMemo(() => {
    if (!todos) return [];
    return todos.filter((todo) => {
      if (statusFilter !== 'ALL' && todo.status !== statusFilter) return false;
      if (sourceFilter !== 'ALL' && todo.source !== sourceFilter) return false;
      if (projectFilter && todo.projectId !== projectFilter) return false;
      if (tagFilter && !todo.tags.includes(tagFilter)) return false;
      if (search) {
        const q = search.toLowerCase();
        if (!todo.title.toLowerCase().includes(q) && !(todo.description ?? '').toLowerCase().includes(q)) return false;
      }
      return true;
    });
  }, [todos, statusFilter, sourceFilter, projectFilter, tagFilter, search]);

  // All unique tags across all todos
  const allTags = useMemo(() => {
    if (!todos) return [];
    const set = new Set<string>();
    todos.forEach((todo) => todo.tags.forEach((tag) => set.add(tag)));
    return Array.from(set).sort();
  }, [todos]);

  // Counts
  const openCount = todos?.filter((t) => t.status === 'OPEN').length ?? 0;
  const inProgressCount = todos?.filter((t) => t.status === 'IN_PROGRESS').length ?? 0;

  const handleQuickAdd = () => {
    if (!quickTitle.trim()) return;
    const req: CreateTodoRequest = {
      title: quickTitle.trim(),
      priority: quickPriority,
      projectId: quickProjectId || undefined,
      tags: quickTags.length > 0 ? quickTags : undefined,
    };
    createTodo.mutate(req, {
      onSuccess: () => {
        setQuickTitle('');
        setQuickPriority('MEDIUM');
        setQuickTags([]);
        quickInputRef.current?.focus();
      },
    });
  };

  const handleToggleStatus = (todo: Todo) => {
    const next: TodoStatus = todo.status === 'DONE' ? 'OPEN' : 'DONE';
    updateStatus.mutate({ id: todo.id, status: next });
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

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-text">{t.todos.title}</h1>
          <p className="text-sm text-text-secondary mt-1">
            {t.todos.subtitle}
            {(openCount > 0 || inProgressCount > 0) && (
              <span className="ml-2 text-text-tertiary">
                ({openCount} {t.todos.statuses.OPEN.toLowerCase()}, {inProgressCount} {t.todos.statuses.IN_PROGRESS.toLowerCase()})
              </span>
            )}
          </p>
        </div>
      </div>

      {/* Quick-Add Bar */}
      <Card>
        <div className="flex items-center gap-3">
          <div className="flex-1">
            <input
              ref={quickInputRef}
              type="text"
              className="input-base w-full"
              placeholder={`${t.todos.create}... (n)`}
              value={quickTitle}
              onChange={(e) => setQuickTitle(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.nativeEvent.isComposing) handleQuickAdd(); }}
            />
          </div>
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
      </div>

      {/* Tag chips */}
      {allTags.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {allTags.map((tag) => (
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

      {/* Todo List */}
      {filtered.length === 0 ? (
        <EmptyState
          icon={<CheckSquare className="w-8 h-8 text-text-tertiary" />}
          title={search || statusFilter !== 'ALL' || tagFilter ? t.common.noResults : t.todos.title}
          description={search || statusFilter !== 'ALL' || tagFilter ? undefined : t.todos.noTodos}
        />
      ) : (
        <Card padding={false}>
          <div className="divide-y divide-border">
            {filtered.map((todo) => (
              <div
                key={todo.id}
                className={`flex items-center gap-3 px-4 py-3 hover:bg-surface-raised/50 transition-colors cursor-pointer group ${
                  todo.status === 'DONE' ? 'opacity-60' : ''
                }`}
                onClick={() => openEdit(todo)}
              >
                {/* Checkbox */}
                <button
                  onClick={(e) => { e.stopPropagation(); handleToggleStatus(todo); }}
                  className={`w-5 h-5 rounded border-2 flex items-center justify-center shrink-0 transition-colors ${
                    todo.status === 'DONE'
                      ? 'bg-success border-success text-white'
                      : 'border-border-strong hover:border-accent'
                  }`}
                >
                  {todo.status === 'DONE' && (
                    <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={3}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                    </svg>
                  )}
                </button>

                {/* Priority */}
                <span className="shrink-0">{priorityIcon(todo.priority)}</span>

                {/* Title + description */}
                <div className="flex-1 min-w-0">
                  <span className={`text-sm font-medium text-text ${todo.status === 'DONE' ? 'line-through' : ''}`}>
                    {todo.title}
                  </span>
                  {todo.description && (
                    <p className="text-xs text-text-tertiary truncate mt-0.5">{todo.description}</p>
                  )}
                </div>

                {/* Project badge */}
                {todo.projectName && (
                  <Badge variant="accent">{todo.projectName}</Badge>
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
                      <span key={tag} className="px-2 py-0.5 rounded-full text-[10px] bg-surface-raised text-text-tertiary border border-border">
                        {tag}
                      </span>
                    ))}
                    {todo.tags.length > 2 && (
                      <span className="text-[10px] text-text-tertiary">+{todo.tags.length - 2}</span>
                    )}
                  </div>
                )}

                {/* Time */}
                <span className="text-xs text-text-tertiary shrink-0 w-16 text-right">
                  {formatRelative(todo.createdAt)}
                </span>

                {/* Actions */}
                <div className="flex items-center gap-1 sm:opacity-0 sm:group-hover:opacity-100 transition-opacity">
                  <button
                    onClick={(e) => { e.stopPropagation(); openEdit(todo); }}
                    className="p-1.5 rounded-lg text-text-tertiary hover:text-accent transition-colors"
                    aria-label={t.common.edit}
                  >
                    <Pencil className="w-3.5 h-3.5" />
                  </button>
                  <button
                    onClick={(e) => { e.stopPropagation(); deleteTodo.mutate(todo.id); }}
                    className="p-1.5 rounded-lg text-text-tertiary hover:text-danger transition-colors"
                    aria-label={t.common.delete}
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        </Card>
      )}

      {/* Edit Modal */}
      <Modal
        open={!!editTodo}
        onClose={() => setEditTodo(null)}
        title={t.todos.editTodo}
        size="lg"
        footer={
          <>
            <Button variant="secondary" onClick={() => setEditTodo(null)}>
              {t.common.cancel}
            </Button>
            <Button
              onClick={handleEditSave}
              loading={updateTodo.isPending}
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
            onChange={(e) => setEditForm({ ...editForm, title: e.target.value })}
          />

          <div className="space-y-1.5">
            <label className="block text-sm font-medium text-text-secondary">{t.todos.descriptionLabel}</label>
            <textarea
              className="input-base w-full min-h-[80px] resize-y"
              value={editForm.description ?? ''}
              onChange={(e) => setEditForm({ ...editForm, description: e.target.value })}
            />
          </div>

          {/* Status buttons */}
          <div className="space-y-1.5">
            <label className="block text-sm font-medium text-text-secondary">{t.common.status}</label>
            <div className="flex gap-2">
              {STATUSES.map((s) => (
                <button
                  key={s}
                  onClick={() => setEditForm({ ...editForm, status: s })}
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
                  onClick={() => setEditForm({ ...editForm, priority: p })}
                  className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors border ${
                    editForm.priority === p
                      ? 'bg-accent text-text-inverse border-accent'
                      : 'bg-surface-raised text-text-secondary border-border hover:border-border-strong'
                  }`}
                >
                  {priorityIcon(p)} {t.todos.priorities[p]}
                </button>
              ))}
            </div>
          </div>

          {/* Project */}
          <Select
            label={t.common.project}
            value={editForm.projectId ?? ''}
            onChange={(e) => setEditForm({ ...editForm, projectId: e.target.value })}
            options={[
              { value: '', label: t.common.selectProject },
              ...(projects?.map((p) => ({ value: p.id, label: p.name })) ?? []),
            ]}
          />

          {/* Tags */}
          <TagInput
            label={t.todos.tags}
            tags={editTagsList}
            onChange={setEditTagsList}
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
    </div>
  );
}
