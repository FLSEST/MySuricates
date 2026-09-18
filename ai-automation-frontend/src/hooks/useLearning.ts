import { keepPreviousData, useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { DashboardData, PlatformStats } from '../types';

interface DashboardQueryOptions {
  /** Keep the previous result on screen while another time range or project loads (`isPlaceholderData`). */
  keepPreviousData?: boolean;
}

export function useDashboard(days = 30, projectId?: string, options: DashboardQueryOptions = {}) {
  const params = new URLSearchParams();
  if (days !== 30) params.set('days', String(days));
  if (projectId) params.set('projectId', projectId);
  const qs = params.toString();
  return useQuery({
    queryKey: ['learning', 'dashboard', days, projectId],
    queryFn: () => api.get<DashboardData>(`/learning/dashboard${qs ? `?${qs}` : ''}`),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
    placeholderData: options.keepPreviousData ? keepPreviousData : undefined,
  });
}

export function usePlatformStats() {
  return useQuery({
    queryKey: ['learning', 'platform-stats'],
    queryFn: () => api.get<PlatformStats>('/learning/platform-stats'),
    staleTime: 60_000,
    refetchOnWindowFocus: true,
  });
}

export function useRateRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ runId, rating }: { runId: string; rating: number; workflowId: string }) =>
      api.post(`/learning/runs/${runId}/rate?rating=${rating}`),
    onSuccess: (_data, variables) => {
      qc.invalidateQueries({ queryKey: ['learning'] });
      qc.invalidateQueries({ queryKey: ['workflows', variables.workflowId, 'runs'] });
      qc.invalidateQueries({ queryKey: ['runs'] });
    },
  });
}
