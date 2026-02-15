import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Workflow, WorkflowRun, CreateWorkflowRequest, UpdateWorkflowRequest } from '../types';

export function useWorkflows() {
  return useQuery({
    queryKey: ['workflows'],
    queryFn: () => api.get<Workflow[]>('/workflows'),
  });
}

export function useWorkflow(id: string) {
  return useQuery({
    queryKey: ['workflows', id],
    queryFn: () => api.get<Workflow>(`/workflows/${id}`),
    enabled: !!id,
  });
}

export function useWorkflowRuns(workflowId: string) {
  return useQuery({
    queryKey: ['workflows', workflowId, 'runs'],
    queryFn: () => api.get<WorkflowRun[]>(`/workflows/${workflowId}/runs`),
    enabled: !!workflowId,
  });
}

export function useCreateWorkflow() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateWorkflowRequest) => api.post<Workflow>('/workflows', data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
  });
}

export function useUpdateWorkflow() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateWorkflowRequest }) =>
      api.put<Workflow>(`/workflows/${id}`, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
  });
}

export function useDeleteWorkflow() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/workflows/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
  });
}

export function useExecuteWorkflow() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, context }: { id: string; context?: Record<string, unknown> }) =>
      api.post<WorkflowRun>(`/workflows/${id}/execute`, { context }),
    onSuccess: (_, variables) => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['workflows', variables.id, 'runs'] });
      qc.invalidateQueries({ queryKey: ['learning', 'dashboard'] });
    },
  });
}

export function usePauseExecution() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (runId: string) => api.post(`/executions/${runId}/pause`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
  });
}

export function useResumeExecution() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (runId: string) => api.post(`/executions/${runId}/resume`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
  });
}

export function useCancelExecution() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (runId: string) => api.post(`/executions/${runId}/cancel`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
  });
}
