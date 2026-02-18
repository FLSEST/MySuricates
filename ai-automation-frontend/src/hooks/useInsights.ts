import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { RunInsights, ExecutionInsights, ToolPatternsResponse } from '../types';

export function useRunInsights(runId: string) {
  return useQuery({
    queryKey: ['insights', 'run', runId],
    queryFn: () => api.get<RunInsights>(`/learning/runs/${runId}/insights`),
    enabled: !!runId,
  });
}

export function useExecutionInsights(workflowId: string) {
  return useQuery({
    queryKey: ['insights', 'execution', workflowId],
    queryFn: () => api.get<ExecutionInsights>(`/learning/workflows/${workflowId}/execution-insights`),
    enabled: !!workflowId,
  });
}

export function useToolPatterns(workflowId: string) {
  return useQuery({
    queryKey: ['insights', 'tool-patterns', workflowId],
    queryFn: () => api.get<ToolPatternsResponse>(`/learning/workflows/${workflowId}/tool-patterns`),
    enabled: !!workflowId,
  });
}
