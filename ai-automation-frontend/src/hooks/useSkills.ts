import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { Skill, Capability, SkillExportData, SkillInput, SkillVersion } from '../types';
import { useModuleEnabled } from './useModules';
import { SKILL_SEAL_PINS_KEY } from './useSealStatus';
import { downloadJSON } from '../utils/downloadFile';

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

/** 409 from the skill write path: a sealed workflow uses the skill (independent of any module switch). */
export function isSkillPinnedBySealError(error: unknown): boolean {
  return !!error && typeof error === 'object'
    && (error as { status?: number }).status === 409
    && (error as { code?: string }).code === 'SKILL_PINNED_BY_SEAL';
}

/** Change history of a skill, newest first; loaded only while `enabled` (e.g. the section is open). */
export function useSkillVersions(skillId: string, enabled = true) {
  const moduleEnabled = useModuleEnabled('skills');
  return useQuery({
    queryKey: ['skills', skillId, 'versions'],
    queryFn: () => api.get<SkillVersion[]>(`/skills/${skillId}/versions`),
    enabled: moduleEnabled && enabled && !!skillId,
  });
}

function useSkillWriteErrorToast() {
  const toast = useToast();
  const t = useT();
  return (err: unknown, fallback: string) => toast.error(
    apiErrorMessage(err, isSkillPinnedBySealError(err) ? t.skills.pinnedBySealError : fallback));
}

function useInvalidateSkills() {
  const qc = useQueryClient();
  return () => {
    qc.invalidateQueries({ queryKey: ['skills'] });
    qc.invalidateQueries({ queryKey: SKILL_SEAL_PINS_KEY });
  };
}

export function useCreateSkill() {
  const invalidate = useInvalidateSkills();
  const showError = useSkillWriteErrorToast();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: SkillInput) => api.post<Skill>('/skills', data),
    onSuccess: () => {
      invalidate();
      toast.success(t.toast.skillCreated);
    },
    onError: (err) => showError(err, t.toast.skillCreateFailed),
  });
}

export function useUpdateSkill() {
  const invalidate = useInvalidateSkills();
  const showError = useSkillWriteErrorToast();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: SkillInput }) => api.put<Skill>(`/skills/${id}`, data),
    onSuccess: () => {
      invalidate();
      toast.success(t.toast.skillUpdated);
    },
    onError: (err) => showError(err, t.toast.skillUpdateFailed),
  });
}

export function useDeleteSkill() {
  const qc = useQueryClient();
  const invalidate = useInvalidateSkills();
  const showError = useSkillWriteErrorToast();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/skills/${id}`),
    onSuccess: (_data, id) => {
      // Its own queries (capabilities, history) would otherwise refetch into a 404 while the detail closes
      qc.removeQueries({ queryKey: ['skills', id] });
      invalidate();
      // Activated skills vanish from workflows (ON DELETE CASCADE)
      qc.invalidateQueries({ queryKey: ['workflows'] });
      toast.success(t.toast.skillDeleted);
    },
    onError: (err) => showError(err, t.toast.skillDeleteFailed),
  });
}

export function useImportSkill() {
  const invalidate = useInvalidateSkills();
  const showError = useSkillWriteErrorToast();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: SkillExportData) => api.post<Skill>('/skills/import', data),
    onSuccess: () => {
      invalidate();
      toast.success(t.toast.skillImported);
    },
    onError: (err) => showError(err, t.toast.skillImportFailed),
  });
}

/** Downloads the skill as a JSON file that `POST /skills/import` accepts. */
export async function exportSkill(id: string, name: string) {
  const data = await api.get<SkillExportData>(`/skills/export/${id}`);
  downloadJSON(data, `${name.replace(/[^a-zA-Z0-9_-]/g, '_')}.skill.json`);
}

/** Minimal shape check for an import file; the backend validates the rest. */
export function isSkillExportData(data: unknown): data is SkillExportData {
  if (!data || typeof data !== 'object') return false;
  const d = data as Record<string, unknown>;
  return typeof d.name === 'string' && d.name.trim() !== '' && typeof d.category === 'string'
    && (d.capabilities === undefined || d.capabilities === null || Array.isArray(d.capabilities));
}
