import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { WorkflowSealStatus } from '../types';
import { useModuleEnabled } from './useModules';

export const SEAL_STATUS_KEY = ['seal-status'] as const;
export const SKILL_SEAL_PINS_KEY = ['skill-seal-pins'] as const;

/**
 * Seal state of every sealed workflow plus refused starts of the last seven days, checked by the
 * backend without starting a run. Deliberately not gated on the `sealing` module: a seal set while
 * the switch was on stays in force after it is turned off, so it must stay visible.
 */
export function useSealStatuses() {
  const query = useQuery({
    queryKey: SEAL_STATUS_KEY,
    queryFn: () => api.get<WorkflowSealStatus[]>('/workflows/seal-status'),
    staleTime: 30_000,
  });
  const byWorkflow = useMemo(() => {
    const map = new Map<string, WorkflowSealStatus>();
    for (const status of query.data ?? []) map.set(status.workflowId, status);
    return map;
  }, [query.data]);
  return { ...query, byWorkflow };
}

export function useSealStatus(workflowId: string | undefined): WorkflowSealStatus | undefined {
  const { byWorkflow } = useSealStatuses();
  return workflowId ? byWorkflow.get(workflowId) : undefined;
}

/** skill id → names of the sealed workflows using it. */
export function useSkillSealPins() {
  const skillsEnabled = useModuleEnabled('skills');
  return useQuery({
    queryKey: SKILL_SEAL_PINS_KEY,
    queryFn: () => api.get<Record<string, string[]>>('/skills/seal-pins'),
    enabled: skillsEnabled,
    staleTime: 30_000,
  });
}
