import { useMemo } from 'react';
import { useAllRuns, useRetryExecution } from '../../../hooks/useWorkflows';
import { useT } from '../../../i18n';
import { runStatusBadge } from '../../../utils/statusBadge';
import type { WorkflowRun, WorkflowRunListItem } from '../../../types';
import { parsePaletteQuery } from '../queryParser';
import type { PaletteContextHandle, PaletteItem } from './types';

function toItem(run: WorkflowRunListItem): PaletteItem {
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
  const retry = useRetryExecution();

  const useResults = (query: string) => {
    const parsed = parsePaletteQuery(query);
    const { data, isLoading, isPlaceholderData } = useAllRuns({
      search: parsed.query || undefined,
      status: parsed.status ?? undefined,
      size: 20,
      page: 0,
    });
    const items = useMemo(() => (data?.content ?? []).map(toItem), [data]);
    // useAllRuns keeps the previous page while the new search loads: those rows answer an earlier query
    return { items, loading: isLoading, pending: isLoading || isPlaceholderData };
  };

  // Pins/Recents for runs intentionally return empty in MVP; rehydrating a run by id
  // would require a separate fetch-by-id hook that doesn't exist today.
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
    getPath: (item) => {
      const run = item.raw as WorkflowRun;
      return `/workflows/${run.workflowId}/run/${run.id}`;
    },
    altAction: {
      icon: 'rotate-cw',
      label: t.commandPalette.rerunHint,
      shortcut: 'r',
      run: (item, { navigate, onClose }) => {
        const run = item.raw as WorkflowRun;
        retry.mutate(run.id, {
          onSuccess: (newRun) => {
            onClose();
            if (newRun?.id && newRun?.workflowId) {
              navigate(`/workflows/${newRun.workflowId}/run/${newRun.id}`);
            } else {
              navigate(`/workflows/${run.workflowId}/run/${run.id}`);
            }
          },
        });
      },
    },
  };
}
