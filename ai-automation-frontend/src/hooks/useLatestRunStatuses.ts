import { useMemo } from 'react';
import { useAllRuns } from './useWorkflows';
import { latestRunStatusMap } from '../utils/latestRunStatus';

/** Map workflowId → most recent run status, from the most recent `size` runs. */
export function useLatestRunStatuses(size = 100) {
  const { data } = useAllRuns({ size });
  return useMemo(() => latestRunStatusMap(data?.content ?? []), [data]);
}
