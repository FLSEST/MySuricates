import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useSettingsStore } from '../stores/settingsStore';
import { downloadFromResponse } from '../utils/downloadFile';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type {
  ProjectBundle, ProjectBundlePreview, ProjectBundleImportRequest, ProjectBundleImportResult,
} from '../types';

/** Fallback filename when the response carries no `Content-Disposition` (spec: `<slug>.mysuricates-bundle.json`). */
export function bundleFilename(projectName: string): string {
  const slug = projectName
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '') || 'project';
  return `${slug}.mysuricates-bundle.json`;
}

/**
 * Downloads a project's bundle export. A raw `fetch` (not the `api` client) because the filename
 * comes from the response's `Content-Disposition` header, which `api.get`'s parsed JSON drops.
 */
export function useExportProjectBundle() {
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: async ({ projectId, fallbackFilename }: { projectId: string; fallbackFilename: string }) => {
      const headers: HeadersInit = {};
      const apiKey = useSettingsStore.getState().apiKey;
      if (apiKey) headers['X-API-Key'] = apiKey;
      const res = await fetch(`/api/v1/projects/${projectId}/export`, { headers });
      if (!res.ok) {
        const body = await res.json().catch(() => ({ error: `HTTP ${res.status}: ${res.statusText}` }));
        throw { ...body, status: res.status, message: body.error };
      }
      await downloadFromResponse(res, fallbackFilename);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.projects.bundle.exportFailed)),
  });
}

/** Preview of a bundle import (`POST /projects/import/preview`) — required before the import button unlocks. */
export function usePreviewProjectBundleImport() {
  return useMutation({
    mutationFn: (bundle: ProjectBundle) =>
      api.post<ProjectBundlePreview>('/projects/import/preview', { bundle }),
  });
}

export function useImportProjectBundle() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: ProjectBundleImportRequest) =>
      api.post<ProjectBundleImportResult>('/projects/import', data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['projects'] });
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['chains'] });
      qc.invalidateQueries({ queryKey: ['schedules'] });
      toast.success(t.projects.bundle.importSuccess);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.projects.bundle.importFailed)),
  });
}
