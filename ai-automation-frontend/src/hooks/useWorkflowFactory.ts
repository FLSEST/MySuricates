import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { WorkflowFactoryRequest, WorkflowFactoryResult, FactoryTopicCategory, Workflow } from '../types';

export function useFactoryTopics() {
  return useQuery({
    queryKey: ['factory', 'topics'],
    queryFn: () => api.get<FactoryTopicCategory[]>('/factory/topics'),
  });
}

export function useFactoryGenerate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (request: WorkflowFactoryRequest) =>
      api.post<Workflow>('/factory/generate', request),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
  });
}

export function useFactoryRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (request: WorkflowFactoryRequest) =>
      api.post<WorkflowFactoryResult>('/factory/run', request),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
  });
}

export function useFactoryVerify() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (workflowId: string) =>
      api.post<WorkflowFactoryResult>(`/factory/verify/${workflowId}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
  });
}

export function useFactoryPromote() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (workflowId: string) =>
      api.post<WorkflowFactoryResult>(`/factory/promote/${workflowId}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['templates'] });
    },
  });
}
