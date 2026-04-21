import { useMemo } from 'react';
import { useAllRuns } from '../../../hooks/useWorkflows';
import { useT } from '../../../i18n';
import { runStatusBadge } from '../../../utils/statusBadge';
import type { WorkflowRun } from '../../../types';
import type { PaletteContextHandle, PaletteItem } from './types';

function toItem(run: WorkflowRun): PaletteItem {
  return {
    id: run.id,
    title: run.workflowName,
    subtitle: run.projectName ?? undefined,
    badge: runStatusBadge(run.status),
    raw: run,
  };
}

export function useRunContext(): PaletteContextHandle {
  const t = useT();

  const useResults = (query: string) => {
    const q = query.trim();
    const { data, isLoading } = useAllRuns({
      search: q || undefined,
      size: 20,
      page: 0,
    });
    const items = useMemo(() => (data?.content ?? []).map(toItem), [data]);
    return { items, loading: isLoading };
  };

  // Pins/Recents for runs are intentionally empty in MVP — runs are mostly one-off;
  // individual run rehydration would require a separate fetch-by-id hook.
  const useByIds = (): PaletteItem[] => [];

  return {
    id: 'run',
    labels: {
      triggerLabel: t.commandPalette.contexts.run.triggerLabel,
      placeholder: t.commandPalette.contexts.run.placeholder,
      empty: t.commandPalette.contexts.run.empty,
    },
    useResults,
    useByIds,
    onEnter: (item, { navigate, onClose }) => {
      const run = item.raw as WorkflowRun;
      onClose();
      navigate(`/workflows/${run.workflowId}/run/${run.id}`);
    },
  };
}
