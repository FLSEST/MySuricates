import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';

export interface ReportEntry {
  name: string;
  label: string;
}

interface ReportContent {
  content: string;
}

export function useReportsList() {
  return useQuery({
    queryKey: ['reports-list'],
    queryFn: () => api.get<ReportEntry[]>('/reports'),
    staleTime: 5 * 60 * 1000,
  });
}

export function useReportContent(name: string | null) {
  return useQuery({
    queryKey: ['report-content', name],
    queryFn: () =>
      api.get<ReportContent>(`/reports/${encodeURIComponent(name!)}`).then(r => r.content),
    enabled: !!name,
    staleTime: 5 * 60 * 1000,
  });
}
