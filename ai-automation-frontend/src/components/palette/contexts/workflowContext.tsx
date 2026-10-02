import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import Fuse, { type FuseResultMatch } from 'fuse.js';
import { api } from '../../../api/client';
import { useWorkflows, useExecuteWorkflow } from '../../../hooks/useWorkflows';
import { extractWorkflowVariables } from '../../../utils/extractWorkflowVariables';
import { useT } from '../../../i18n';
import type { Workflow } from '../../../types';
import type { PaletteContextHandle, PaletteItem, PaletteMatch, HighlightKey } from './types';

interface SemanticHit {
  workflowId: string;
  workflowName: string;
  reason: string;
  score: number;
}

interface WorkflowWithSearchable extends Workflow {
  _tagsJoined: string;
}

const FUSE_OPTIONS = {
  includeMatches: true,
  ignoreLocation: true,
  threshold: 0.35,
  minMatchCharLength: 2,
  keys: [
    { name: 'name', weight: 3 },
    { name: 'description', weight: 1.5 },
    { name: '_tagsJoined', weight: 2 },
    { name: 'category', weight: 1 },
  ],
};

function mapFuseMatches(matches: readonly FuseResultMatch[] | undefined): PaletteMatch[] {
  if (!matches) return [];
  const out: PaletteMatch[] = [];
  for (const m of matches) {
    let key: HighlightKey | null = null;
    if (m.key === 'name') key = 'title';
    else if (m.key === 'description') key = 'subtitle';
    // tags and category don't map to title/subtitle; skip visual highlighting for those
    if (!key || !m.indices?.length) continue;
    out.push({
      key,
      indices: m.indices.map(([a, b]) => [a, b] as [number, number]),
    });
  }
  return out;
}

function toItem(w: Workflow, subtitleOverride?: string, matches?: PaletteMatch[]): PaletteItem {
  return {
    id: w.id,
    title: w.name,
    subtitle: subtitleOverride ?? w.description ?? undefined,
    icon: w.icon,
    matches,
    raw: w,
  };
}

export function useWorkflowContext(): PaletteContextHandle {
  const t = useT();
  const { data: workflows = [] } = useWorkflows();
  const executeWorkflow = useExecuteWorkflow();

  const searchable: WorkflowWithSearchable[] = useMemo(
    () => workflows.map((w) => ({ ...w, _tagsJoined: (w.tags ?? []).join(' ') })),
    [workflows],
  );
  const fuse = useMemo(() => new Fuse(searchable, FUSE_OPTIONS), [searchable]);

  const useResults = (query: string) => {
    const q = query.trim();
    const localMatches = useMemo(() => (q ? fuse.search(q).slice(0, 10) : []), [q]);

    const semantic = useQuery({
      queryKey: ['search', 'semantic', q],
      queryFn: async () =>
        api.post<SemanticHit[]>('/search/workflows/semantic', { query: q, limit: 5 }),
      enabled: q.length >= 3,
      staleTime: 60_000,
      // Only a supplement: a failure ends the wait of Enter at once instead of after the retries (c5f3cff5)
      retry: false,
    });

    const items = useMemo(() => {
      const localIds = new Set(localMatches.map((r) => r.item.id));
      const localItems = localMatches.map((r) => toItem(r.item, undefined, mapFuseMatches(r.matches)));
      const semanticItems = (semantic.data ?? [])
        .filter((h) => !localIds.has(h.workflowId))
        .map((h) => {
          const w = workflows.find((x) => x.id === h.workflowId);
          return w ? toItem(w, h.reason) : null;
        })
        .filter((i): i is PaletteItem => i !== null);
      return [...localItems, ...semanticItems];
    }, [localMatches, semantic.data]);

    return { items, loading: semantic.isLoading };
  };

  const useByIds = (ids: string[]): PaletteItem[] => {
    const byId = new Map(workflows.map((w) => [w.id, w]));
    return ids.map((id) => byId.get(id)).filter((w): w is Workflow => !!w).map((w) => toItem(w));
  };

  return {
    id: 'workflow',
    labels: {
      triggerLabel: t.commandPalette.contexts.workflow.triggerLabel,
      placeholder: t.commandPalette.contexts.workflow.placeholder,
      empty: t.commandPalette.contexts.workflow.empty,
    },
    useResults,
    useByIds,
    onEnter: () => { /* handled via onBeforeEnter */ },
    onOpen: (item, { navigate, onClose }) => {
      onClose();
      navigate(`/workflows/${item.id}`);
    },
    getPath: (item) => `/workflows/${item.id}`,
    onBeforeEnter: (item, deps, showCollector) => {
      const w = item.raw as Workflow;
      const variables = extractWorkflowVariables(w.blocks);
      if (variables.length > 0) {
        const defaults: Record<string, string> = {};
        const ctx = w.defaultContext ?? {};
        for (const v of variables) {
          defaults[v] = typeof ctx[v] === 'string' ? (ctx[v] as string) : '';
        }
        showCollector(w, variables, defaults);
        return true;
      }
      executeWorkflow.mutate(
        { id: w.id },
        {
          onSuccess: (run) => {
            deps.onClose();
            deps.navigate(`/workflows/${w.id}/run/${run.id}`);
          },
        },
      );
      return true;
    },
  };
}
