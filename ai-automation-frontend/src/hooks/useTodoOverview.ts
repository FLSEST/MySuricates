import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { TodoOverviewResponse } from '../types';

/** Cross-project cockpit overview (per-project rollups + "Braucht dich" lane). */
export function useTodoOverview() {
  return useQuery({
    queryKey: ['todos', 'overview'],
    queryFn: () => api.get<TodoOverviewResponse>('/todos/overview'),
    refetchInterval: 60_000,
    staleTime: 60_000,
  });
}
