import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { TodoTrigger, CreateTodoTriggerRequest } from '../types';

export function useTodoTriggers() {
  return useQuery({
    queryKey: ['todo-triggers'],
    queryFn: () => api.get<TodoTrigger[]>('/todo-triggers'),
  });
}

/** TODO triggers whose target is this workflow. */
export function useTodoTriggersForWorkflow(workflowId: string) {
  return useQuery({
    queryKey: ['todo-triggers', 'workflow', workflowId],
    queryFn: () => api.get<TodoTrigger[]>(`/todo-triggers/workflow/${workflowId}`),
    enabled: !!workflowId,
  });
}

export function useCreateTodoTrigger() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: CreateTodoTriggerRequest) =>
      api.post<TodoTrigger>('/todo-triggers', data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['todo-triggers'] });
      toast.success(t.todoTriggers.created);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.todoTriggers.createFailed)),
  });
}

export function useToggleTodoTrigger() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, enable }: { id: string; enable: boolean }) =>
      api.post<TodoTrigger>(`/todo-triggers/${id}/${enable ? 'enable' : 'disable'}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['todo-triggers'] });
      toast.success(t.todoTriggers.toggled);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.todoTriggers.toggleFailed)),
  });
}

export function useDeleteTodoTrigger() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/todo-triggers/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['todo-triggers'] });
      toast.success(t.todoTriggers.deleted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.todoTriggers.deleteFailed)),
  });
}
