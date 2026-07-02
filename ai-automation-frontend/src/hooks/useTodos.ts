import { useQuery, useMutation, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { Todo, CreateTodoRequest, UpdateTodoRequest, TodoCountResponse, TodoStatus, PageResponse, WorkflowRun } from '../types';

export function useTodos(params?: Record<string, string>, page = 0, size = 50) {
  return useQuery({
    queryKey: ['todos', params, page, size],
    queryFn: () => {
      const searchParams = new URLSearchParams();
      searchParams.set('page', String(page));
      searchParams.set('size', String(size));
      if (params) {
        Object.entries(params).forEach(([k, v]) => { if (v) searchParams.set(k, v); });
      }
      return api.get<PageResponse<Todo>>(`/todos?${searchParams.toString()}`);
    },
    refetchInterval: (query) => {
      const data = query.state.data;
      const hasResolvingTodos = data?.content?.some((todo) => todo.status === 'IN_PROGRESS' && !!todo.lastFixRunId);
      return hasResolvingTodos ? 5_000 : 60_000;
    },
    staleTime: 60_000,
    placeholderData: keepPreviousData,
  });
}

export function useTodo(id: string) {
  return useQuery({
    queryKey: ['todos', id],
    queryFn: () => api.get<Todo>(`/todos/${id}`),
    enabled: !!id,
    refetchInterval: (query) => {
      const data = query.state.data;
      return data?.status === 'IN_PROGRESS' && data.lastFixRunId ? 5_000 : false;
    },
  });
}

export function useTodoCount() {
  return useQuery({
    queryKey: ['todos', 'count'],
    queryFn: () => api.get<TodoCountResponse>('/todos/count'),
    refetchInterval: 60_000,
    staleTime: 60_000,
  });
}

/** Lazily fetch a project's active todos (OPEN + IN_PROGRESS) — used by the cockpit's expandable cards. */
export function useProjectActiveTodos(projectId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['todos', 'project-active', projectId],
    queryFn: () => {
      const sp = new URLSearchParams();
      sp.set('projectId', projectId);
      sp.append('status', 'OPEN');
      sp.append('status', 'IN_PROGRESS');
      sp.set('size', '12');
      return api.get<PageResponse<Todo>>(`/todos?${sp.toString()}`);
    },
    enabled: enabled && !!projectId,
    staleTime: 30_000,
  });
}

export function useCreateTodo() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: CreateTodoRequest) => api.post<Todo>('/todos', data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['todos'] });
      toast.success(t.toast.todoCreated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.todoCreateFailed)),
  });
}

export function useUpdateTodo() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateTodoRequest }) =>
      api.put<Todo>(`/todos/${id}`, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['todos'] });
      toast.success(t.toast.todoUpdated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.todoUpdateFailed)),
  });
}

export function useUpdateTodoStatus() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: TodoStatus }) =>
      api.patch<Todo>(`/todos/${id}/status`, { status }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['todos'] });
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.todoUpdateFailed)),
  });
}

export function useBulkUpdateTodoStatus() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ ids, status }: { ids: string[]; status: TodoStatus }) =>
      api.post<{ updated: number }>('/todos/bulk-status', { ids, status }),
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ['todos'] });
      toast.success(t.toast.todosArchived.replace('{n}', String(res.updated)));
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.todoUpdateFailed)),
  });
}

export function useArchiveStale() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (projectId?: string) =>
      api.post<{ archived: number }>(`/todos/archive-stale${projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''}`),
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ['todos'] });
      toast.success(t.toast.todosArchived.replace('{n}', String(res.archived)));
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.todoUpdateFailed)),
  });
}

export function useDeleteTodo() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/todos/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['todos'] });
      toast.success(t.toast.todoDeleted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.todoDeleteFailed)),
  });
}

export interface TodoExportResult {
  referenceType: string;
  referenceId: string;
  url: string | null;
}

/** Export a single TODO to a connected task tracker (GitHub, Jira, …). Provider/target
 *  default to the project's config.taskExport when not passed. */
export function useExportTodo() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, provider, target }: { id: string; provider?: string; target?: string }) =>
      api.post<TodoExportResult>(`/todos/${id}/export`, { provider, target }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['todos'] });
      toast.success(t.toast.todoExported);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.todoExportFailed)),
  });
}

export function useResolveTodo() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.post<WorkflowRun>(`/todos/${id}/resolve`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['todos'] });
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['runs'] });
      toast.success(t.toast.workflowExecuted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.workflowExecuteFailed)),
  });
}
