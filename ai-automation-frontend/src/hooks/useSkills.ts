import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { Skill, Capability } from '../types';
import { useModuleEnabled } from './useModules';

export function useSkills() {
  const moduleEnabled = useModuleEnabled('skills');
  return useQuery({
    queryKey: ['skills'],
    queryFn: () => api.get<Skill[]>('/skills'),
    enabled: moduleEnabled,
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
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ workflowId, skillIds }: { workflowId: string; skillIds: string[] }) =>
      api.post(`/workflows/${workflowId}/skills`, { skillIds }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      toast.success(t.toast.skillsActivated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.skillsActivateFailed)),
  });
}

export function useDeactivateSkill() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ workflowId, skillId }: { workflowId: string; skillId: string }) =>
      api.delete(`/workflows/${workflowId}/skills/${skillId}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      toast.success(t.toast.skillDeactivated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.skillDeactivateFailed)),
  });
}
