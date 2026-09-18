import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { RequestMetricRoute } from '../types';

/**
 * Measured cost per API route since the last daily snapshot, most expensive (p95) first.
 *
 * Polls slowly on purpose: the numbers describe a rolling window, not a live value, and the
 * request that fetches them is itself measured — a fast interval would mostly show this widget
 * asking about itself.
 */
export function useRequestMetrics() {
  return useQuery({
    queryKey: ['system', 'request-metrics'],
    queryFn: () => api.get<RequestMetricRoute[]>('/system/request-metrics'),
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });
}
