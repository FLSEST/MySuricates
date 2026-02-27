import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { DashboardData } from '../types';

export function useDashboard() {
  return useQuery({
    queryKey: ['learning', 'dashboard'],
    queryFn: () => api.get<DashboardData>('/learning/dashboard'),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
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
