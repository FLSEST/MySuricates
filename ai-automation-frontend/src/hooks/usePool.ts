import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useI18nStore, useT } from '../i18n';
import type { PoolWorkflowEntry, ExportToPoolRequest, ExportToPoolResponse, PoolScope } from '../types';

export function usePoolWorkflows({ enabled = true }: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: ['pool'],
    queryFn: () => api.get<PoolWorkflowEntry[]>('/pool'),
    enabled,
  });
}

export function useInstallPoolWorkflow() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  // The server installs in this language: name, description and — for bilingual pool files — the steps
  const locale = useI18nStore((s) => s.locale);
  return useMutation({
    mutationFn: ({ poolId, projectId, name, forceCreate }: { poolId: string; projectId: string; name?: string; forceCreate?: boolean }) =>
      api.post<{ workflowId: string; workflowName: string; created: boolean; integrity?: 'VERIFIED' | 'MISSING' }>(
        `/pool/${poolId}/install`, { projectId, name, forceCreate, locale }),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      toast.success(data.created ? t.toast.poolWorkflowInstalled : t.toast.poolWorkflowUpdated);
      // An older backend sends no `integrity` — then there is nothing to warn about (b18939a6)
      if (data.integrity === 'MISSING') toast.warning(t.toast.poolIntegrityMissing);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.poolInstallFailed)),
  });
}

export function usePoolExport() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ workflowId, request, scope }: { workflowId: string; request?: ExportToPoolRequest; scope?: PoolScope }) =>
      api.post<ExportToPoolResponse>(
        `/pool/export/${encodeURIComponent(workflowId)}?scope=${encodeURIComponent(scope ?? 'portable')}`,
        request ?? {},
      ),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ['pool'] });
      if (data.sanitizedPaths.length > 0) {
        toast.success(`${t.factory.exportedToPool} ${t.factory.exportSanitizedPaths.replace('{paths}', data.sanitizedPaths.join(', '))}`);
      } else {
        toast.success(t.factory.exportedToPool);
      }
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.factory.exportFailed)),
  });
}
