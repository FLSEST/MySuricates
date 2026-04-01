import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { Todo, CreateTodoRequest, UpdateTodoRequest, TodoCountResponse, TodoStatus, PageResponse } from '../types';

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
    refetchInterval: 15_000,
    staleTime: 10_000,
  });
}

export function useTodo(id: string) {
  return useQuery({
    queryKey: ['todos', id],
    queryFn: () => api.get<Todo>(`/todos/${id}`),
    enabled: !!id,
  });
}

export function useTodoCount() {
  return useQuery({
    queryKey: ['todos', 'count'],
    queryFn: () => api.get<TodoCountResponse>('/todos/count'),
    refetchInterval: 15_000,
    staleTime: 10_000,
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
