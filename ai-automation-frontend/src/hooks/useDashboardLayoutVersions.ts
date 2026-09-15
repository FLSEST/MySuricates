import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { DashboardLayout } from '../components/dashboard/dashboardLayout';
import type { DashboardLayoutVersion, DashboardLayoutVersionSummary, PageResponse } from '../types';

const BASE = '/dashboard/layouts';

export const DASHBOARD_LAYOUT_VERSIONS_KEY = ['dashboard-layout-versions'] as const;

export interface SaveDashboardLayoutRequest {
  deviceLabel: string;
  formatVersion: number;
  layout: DashboardLayout;
  baseVersionId: string | null;
  force: boolean;
}

export function fetchLatestDashboardLayoutVersion(): Promise<DashboardLayoutVersionSummary | null> {
  return api
    .get<PageResponse<DashboardLayoutVersionSummary>>(`${BASE}?page=0&size=1`)
    .then((page) => page.content[0] ?? null);
}

export function fetchDashboardLayoutVersion(id: string): Promise<DashboardLayoutVersion> {
  return api.get<DashboardLayoutVersion>(`${BASE}/${encodeURIComponent(id)}`);
}

export function saveDashboardLayoutVersion(request: SaveDashboardLayoutRequest): Promise<DashboardLayoutVersionSummary> {
  return api.post<DashboardLayoutVersionSummary>(BASE, request);
}

/** Default of automation.dashboard.layout.max-request-bytes (DashboardLayoutSizeLimitFilter). */
export const MAX_LAYOUT_REQUEST_BYTES = 262_144;

/** True when the server's size filter would reject this save request (UTF-8 bytes of the JSON body). */
export function exceedsLayoutRequestLimit(request: SaveDashboardLayoutRequest): boolean {
  return new TextEncoder().encode(JSON.stringify(request)).length > MAX_LAYOUT_REQUEST_BYTES;
}

export function useDashboardLayoutVersions(page: number, size = 20, enabled = true) {
  return useQuery({
    queryKey: [...DASHBOARD_LAYOUT_VERSIONS_KEY, page, size],
    queryFn: () => api.get<PageResponse<DashboardLayoutVersionSummary>>(`${BASE}?page=${page}&size=${size}`),
    enabled,
  });
}

export function useDashboardLayoutVersion(id: string, enabled: boolean) {
  return useQuery({
    queryKey: [...DASHBOARD_LAYOUT_VERSIONS_KEY, 'detail', id],
    queryFn: () => fetchDashboardLayoutVersion(id),
    enabled,
    // Versions never change their content.
    staleTime: Infinity,
  });
}

export function useRenameDashboardLayoutVersion() {
  const queryClient = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, name }: { id: string; name: string | null }) =>
      api.patch<DashboardLayoutVersionSummary>(`${BASE}/${encodeURIComponent(id)}`, { name }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DASHBOARD_LAYOUT_VERSIONS_KEY });
      toast.success(t.dashboard.history.renamed);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.dashboard.history.renameFailed)),
  });
}
