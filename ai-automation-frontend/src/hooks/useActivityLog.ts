import { useEffect, useRef } from 'react';
import { useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import { api } from '../api/client';
import type { ActivityLogPage } from '../types';
import { useTopicSocket } from './useTopicSocket';

/** Polling only while the activity-log topic is not connected (old backend, network, no ticket). */
export const ACTIVITY_LOG_FALLBACK_POLL_MS = 10_000;
/** A run writes entries in bursts; one refetch per window instead of one per entry. */
export const ACTIVITY_LOG_REFETCH_THROTTLE_MS = 1_000;

/**
 * Reloads the activity log when the backend reports a new entry on `/topic/activity-log` (TODO 4eea7a87)
 * instead of polling every 10 s; while that connection is down it polls as before.
 */
function useActivityLogLiveRefresh(): boolean {
  const queryClient = useQueryClient();
  const pending = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => {
    if (pending.current) clearTimeout(pending.current);
  }, []);

  return useTopicSocket('/topic/activity-log', true, () => {
    if (pending.current) return;
    pending.current = setTimeout(() => {
      pending.current = null;
      void queryClient.invalidateQueries({ queryKey: ['activity-log'] });
    }, ACTIVITY_LOG_REFETCH_THROTTLE_MS);
  });
}

export function useActivityLog(
  page: number,
  size: number,
  level?: string,
  category?: string,
  workflowId?: string,
) {
  const live = useActivityLogLiveRefresh();
  const params = new URLSearchParams();
  params.set('page', String(page));
  params.set('size', String(size));
  if (level) params.set('level', level);
  if (category) params.set('category', category);
  if (workflowId) params.set('workflowId', workflowId);

  return useQuery({
    queryKey: ['activity-log', page, size, level, category, workflowId],
    queryFn: () => api.get<ActivityLogPage>(`/activity-log?${params.toString()}`),
    refetchInterval: live ? false : ACTIVITY_LOG_FALLBACK_POLL_MS,
    placeholderData: keepPreviousData,
  });
}
