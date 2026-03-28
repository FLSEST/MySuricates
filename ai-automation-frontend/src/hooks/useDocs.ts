import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';

export interface DocEntry {
  name: string;
  label: string;
  lang: 'de' | 'en';
}

export interface GroupedDocEntry {
  label: string;
  de: string | null;
  en: string | null;
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

export function useGroupedDocs() {
  const query = useDocsList();
  const grouped: GroupedDocEntry[] = [];

  if (query.data) {
    const map = new Map<string, GroupedDocEntry>();
    for (const entry of query.data) {
      // Use label as grouping key (same label for DE/EN versions of same doc)
      const key = entry.label;
      if (!map.has(key)) {
        map.set(key, { label: entry.label, de: null, en: null });
      }
      const g = map.get(key)!;
      if (entry.lang === 'de') g.de = entry.name;
      else if (entry.lang === 'en') g.en = entry.name;
    }
    grouped.push(...map.values());
  }

  return { ...query, grouped };
}

export function useDocContent(name: string | null, lang: 'de' | 'en' = 'de') {
  return useQuery({
    queryKey: ['doc-content', name, lang],
    queryFn: () =>
      api.get<DocContent>(`/docs/${encodeURIComponent(name!)}?lang=${lang}`).then(r => r.content),
    enabled: !!name,
    staleTime: 5 * 60 * 1000,
  });
}
