import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { Todo, CreateTodoRequest, UpdateTodoRequest, TodoCountResponse, TodoStatus } from '../types';

export function useTodos(params?: Record<string, string>) {
  const searchParams = params ? '?' + new URLSearchParams(params).toString() : '';
  return useQuery({
    queryKey: ['todos', params],
    queryFn: () => api.get<Todo[]>(`/todos${searchParams}`),
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
    onError: () => toast.error(t.toast.todoCreateFailed),
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
    onError: () => toast.error(t.toast.todoUpdateFailed),
  });
}

export function useUpdateTodoStatus() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: TodoStatus }) =>
      api.patch<Todo>(`/todos/${id}/status`, { status }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['todos'] });
    },
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
    onError: () => toast.error(t.toast.todoDeleteFailed),
  });
}
