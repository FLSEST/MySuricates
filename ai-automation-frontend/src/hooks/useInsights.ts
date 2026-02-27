import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { RunInsights } from '../types';

export function useRunInsights(runId: string) {
  return useQuery({
    queryKey: ['insights', 'run', runId],
    queryFn: () => api.get<RunInsights>(`/learning/runs/${runId}/insights`),
    enabled: !!runId,
  });
}
