import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';

interface DocEntry {
  name: string;
  label: string;
}

interface DocContent {
  content: string;
}

export function useDocsList() {
  return useQuery({
    queryKey: ['docs-list'],
    queryFn: () => api.get<DocEntry[]>('/docs'),
    staleTime: 5 * 60 * 1000,
  });
}

export function useDocContent(name: string | null) {
  return useQuery({
    queryKey: ['doc-content', name],
    queryFn: () => api.get<DocContent>(`/docs/${encodeURIComponent(name!)}`).then(r => r.content),
    enabled: !!name,
    staleTime: 5 * 60 * 1000,
  });
}
