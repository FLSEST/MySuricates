import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../../api/client';
import { useWorkflows, useExecuteWorkflow } from '../../../hooks/useWorkflows';
import { extractWorkflowVariables } from '../../../utils/extractWorkflowVariables';
import { useT } from '../../../i18n';
import type { Workflow } from '../../../types';
import type { PaletteContextHandle, PaletteItem, ActionDeps } from './types';

interface SemanticHit {
  workflowId: string;
  workflowName: string;
  reason: string;
  score: number;
}

function rankLocal(workflows: Workflow[], q: string): Workflow[] {
  const query = q.toLowerCase().trim();
  if (!query) return [];
  return workflows
    .map((w) => {
      let score = 0;
      const name = w.name.toLowerCase();
      const desc = (w.description ?? '').toLowerCase();
      const cat = (w.category ?? '').toLowerCase();
      const tags = (w.tags ?? []).join(' ').toLowerCase();
      if (name === query) score += 10;
      if (name.startsWith(query)) score += 5;
      if (name.includes(query)) score += 3;
      if (desc.includes(query)) score += 1.5;
      if (tags.includes(query)) score += 2;
      if (cat.includes(query)) score += 1;
      return { w, score };
    })
    .filter((s) => s.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, 10)
    .map((s) => s.w);
}

function toItem(w: Workflow, subtitleOverride?: string): PaletteItem {
  return {
    id: w.id,
    title: w.name,
    subtitle: subtitleOverride ?? w.description ?? undefined,
    icon: w.icon,
    raw: w,
  };
}

export function useWorkflowContext(): PaletteContextHandle {
  const t = useT();
  const { data: workflows = [] } = useWorkflows();
  const executeWorkflow = useExecuteWorkflow();

  const useResults = (query: string) => {
    const q = query.trim();
    const localMatches = useMemo(() => rankLocal(workflows, q), [q]); // eslint-disable-line react-hooks/exhaustive-deps

    const semantic = useQuery({
      queryKey: ['search', 'semantic', q],
      queryFn: async () =>
        api.post<SemanticHit[]>('/search/workflows/semantic', { query: q, limit: 5 }),
      enabled: q.length >= 3,
      staleTime: 60_000,
    });

    const items = useMemo(() => {
      const localIds = new Set(localMatches.map((w) => w.id));
      const semanticItems = (semantic.data ?? [])
        .filter((h) => !localIds.has(h.workflowId))
        .map((h) => {
          const w = workflows.find((x) => x.id === h.workflowId);
          return w ? toItem(w, h.reason) : null;
        })
        .filter((i): i is PaletteItem => i !== null);
      return [...localMatches.map((w) => toItem(w)), ...semanticItems];
    }, [localMatches, semantic.data]); // eslint-disable-line react-hooks/exhaustive-deps

    return { items, loading: semantic.isLoading };
  };

  const useByIds = (ids: string[]): PaletteItem[] => {
    const set = new Set(ids);
    const byId = new Map(workflows.map((w) => [w.id, w]));
    return ids.map((id) => byId.get(id)).filter((w): w is Workflow => !!w && set.has(w.id)).map((w) => toItem(w));
  };

  const onEnter = (item: PaletteItem, deps: ActionDeps) => {
    // handled by onBeforeEnter (variable collection), see CommandPalette wiring.
    void item;
    void deps;
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
    onEnter,
    onOpen: (item, { navigate, onClose }) => {
      onClose();
      navigate(`/workflows/${item.id}`);
    },
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
        return true; // intercept
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
