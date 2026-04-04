import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { DashboardData, PlatformStats } from '../types';

export function useDashboard(days = 30, projectId?: string) {
  const params = new URLSearchParams();
  if (days !== 30) params.set('days', String(days));
  if (projectId) params.set('projectId', projectId);
  const qs = params.toString();
  return useQuery({
    queryKey: ['learning', 'dashboard', days, projectId],
    queryFn: () => api.get<DashboardData>(`/learning/dashboard${qs ? `?${qs}` : ''}`),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
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
    mutationFn: ({ runId, rating }: { runId: string; rating: number }) =>
      api.post(`/learning/runs/${runId}/rate?rating=${rating}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['learning'] }),
  });
}
