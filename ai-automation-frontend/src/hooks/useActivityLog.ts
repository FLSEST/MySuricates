import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { ActivityLogPage } from '../types';

export function useActivityLog(
  page: number,
  size: number,
  level?: string,
  category?: string,
) {
  const params = new URLSearchParams();
  params.set('page', String(page));
  params.set('size', String(size));
  if (level) params.set('level', level);
  if (category) params.set('category', category);

  return useQuery({
    queryKey: ['activity-log', page, size, level, category],
    queryFn: () => api.get<ActivityLogPage>(`/activity-log?${params.toString()}`),
    refetchInterval: 10_000,
  });
}
