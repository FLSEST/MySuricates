import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Skill, Capability } from '../types';

export function useSkills() {
  return useQuery({
    queryKey: ['skills'],
    queryFn: () => api.get<Skill[]>('/skills'),
  });
}

export function useSkill(id: string) {
  return useQuery({
    queryKey: ['skills', id],
    queryFn: () => api.get<Skill>(`/skills/${id}`),
    enabled: !!id,
  });
}

export function useSkillCapabilities(skillId: string) {
  return useQuery({
    queryKey: ['skills', skillId, 'capabilities'],
    queryFn: () => api.get<Capability[]>(`/skills/${skillId}/capabilities`),
    enabled: !!skillId,
  });
}

export function useActivateSkills() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ workflowId, skillIds }: { workflowId: string; skillIds: string[] }) =>
      api.post(`/workflows/${workflowId}/skills`, { skillIds }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
  });
}

export function useDeactivateSkill() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ workflowId, skillId }: { workflowId: string; skillId: string }) =>
      api.delete(`/workflows/${workflowId}/skills/${skillId}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
  });
}
