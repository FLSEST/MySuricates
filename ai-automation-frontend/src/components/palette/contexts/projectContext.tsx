import { useMemo } from 'react';
import { useProjects } from '../../../hooks/useProjects';
import { useT } from '../../../i18n';
import { projectStatusBadge } from '../../../utils/statusBadge';
import type { Project } from '../../../types';
import type { PaletteContextHandle, PaletteItem } from './types';

function toItem(p: Project): PaletteItem {
  return {
    id: p.id,
    title: p.name,
    subtitle: p.subtitle ?? p.description ?? undefined,
    badge: projectStatusBadge(p.status),
    raw: p,
  };
}

function fuzzyScore(p: Project, q: string): number {
  if (!q) return 0;
  const name = p.name.toLowerCase();
  const sub = (p.subtitle ?? '').toLowerCase();
  const desc = (p.description ?? '').toLowerCase();
  let score = 0;
  if (name === q) score += 10;
  if (name.startsWith(q)) score += 5;
  if (name.includes(q)) score += 3;
  if (sub.includes(q)) score += 2;
  if (desc.includes(q)) score += 1;
  return score;
}

export function useProjectContext(): PaletteContextHandle {
  const t = useT();
  const { data: projects = [], isLoading } = useProjects();

  const useResults = (query: string) => {
    const q = query.trim().toLowerCase();
    const items = useMemo(() => {
      if (!q) return [];
      return projects
        .map((p) => ({ p, score: fuzzyScore(p, q) }))
        .filter((s) => s.score > 0)
        .sort((a, b) => b.score - a.score)
        .slice(0, 10)
        .map((s) => toItem(s.p));
    }, [q]); // eslint-disable-line react-hooks/exhaustive-deps
    return { items, loading: isLoading };
  };

  const useByIds = (ids: string[]): PaletteItem[] => {
    const byId = new Map(projects.map((p) => [p.id, p]));
    return ids.map((id) => byId.get(id)).filter((p): p is Project => !!p).map(toItem);
  };

  return {
    id: 'project',
    labels: {
      triggerLabel: t.commandPalette.contexts.project.triggerLabel,
      placeholder: t.commandPalette.contexts.project.placeholder,
      empty: t.commandPalette.contexts.project.empty,
    },
    useResults,
    useByIds,
    onEnter: (item, { navigate, onClose }) => {
      onClose();
      navigate(`/projects/${item.id}`);
    },
  };
}
