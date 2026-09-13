import { useMemo } from 'react';
import Fuse, { type FuseResultMatch } from 'fuse.js';
import { useProjects } from '../../../hooks/useProjects';
import { useT } from '../../../i18n';
import { projectStatusBadge } from '../../../utils/statusBadge';
import type { Project } from '../../../types';
import type { PaletteContextHandle, PaletteItem, PaletteMatch, HighlightKey } from './types';

const FUSE_OPTIONS = {
  includeMatches: true,
  ignoreLocation: true,
  threshold: 0.35,
  minMatchCharLength: 2,
  keys: [
    { name: 'name', weight: 3 },
    { name: 'subtitle', weight: 1.5 },
    { name: 'description', weight: 1 },
  ],
};

function mapFuseMatches(matches: readonly FuseResultMatch[] | undefined): PaletteMatch[] {
  if (!matches) return [];
  const out: PaletteMatch[] = [];
  for (const m of matches) {
    let key: HighlightKey | null = null;
    if (m.key === 'name') key = 'title';
    else if (m.key === 'subtitle' || m.key === 'description') key = 'subtitle';
    if (!key || !m.indices?.length) continue;
    out.push({ key, indices: m.indices.map(([a, b]) => [a, b] as [number, number]) });
  }
  return out;
}

function toItem(p: Project, matches?: PaletteMatch[]): PaletteItem {
  return {
    id: p.id,
    title: p.name,
    subtitle: p.subtitle ?? p.description ?? undefined,
    badge: projectStatusBadge(p.status),
    matches,
    raw: p,
  };
}

export function useProjectContext(): PaletteContextHandle {
  const t = useT();
  const { data: projects = [], isLoading } = useProjects();

  const fuse = useMemo(() => new Fuse(projects, FUSE_OPTIONS), [projects]);

  const useResults = (query: string) => {
    const q = query.trim();
    const items = useMemo(() => {
      if (!q) return [];
      return fuse.search(q).slice(0, 10).map((r) => toItem(r.item, mapFuseMatches(r.matches)));
    }, [q]);
    return { items, loading: isLoading };
  };

  const useByIds = (ids: string[]): PaletteItem[] => {
    const byId = new Map(projects.map((p) => [p.id, p]));
    return ids.map((id) => byId.get(id)).filter((p): p is Project => !!p).map((p) => toItem(p));
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
    getPath: (item) => `/projects/${item.id}`,
  };
}
