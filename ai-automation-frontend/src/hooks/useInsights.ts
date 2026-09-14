import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { RunInsights } from '../types';
import { useModuleEnabled } from './useModules';

export function useRunInsights(runId: string) {
  const moduleEnabled = useModuleEnabled('learning');
  return useQuery({
    queryKey: ['insights', 'run', runId],
    queryFn: () => api.get<RunInsights>(`/learning/runs/${runId}/insights`),
    enabled: moduleEnabled && !!runId,
  });
}
