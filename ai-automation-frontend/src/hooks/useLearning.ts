import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { DashboardData, WorkflowStats } from '../types';

export function useDashboard() {
  return useQuery({
    queryKey: ['learning', 'dashboard'],
    queryFn: () => api.get<DashboardData>('/learning/dashboard'),
  });
}

export function useWorkflowStats(workflowId: string) {
  return useQuery({
    queryKey: ['learning', 'stats', workflowId],
    queryFn: () => api.get<WorkflowStats>(`/learning/workflows/${workflowId}/stats`),
    enabled: !!workflowId,
  });
}

export function useRateRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ runId, rating }: { runId: string; rating: number }) =>
      api.post(`/learning/runs/${runId}/rate`, { rating }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['learning'] }),
  });
}
