import { useEffect, useState } from 'react';
import { useExtractMemory } from './useWorkflowMemory';

/**
 * Whether an extraction can start. It learns from runs, so a workflow without any cannot — even when it
 * already has memory versions from runs that were deleted since (442702e2); the panel then says why instead
 * of only greying the button. `runCount` undefined means the runs are still loading.
 */
export function extractionAvailability(runCount: number | undefined): 'ready' | 'loading' | 'noRuns' {
  if (runCount === undefined) return 'loading';
  return runCount > 0 ? 'ready' : 'noRuns';
}

/**
 * State of the "extract knowledge" dialog of a workflow: which runs to learn from (none chosen
 * means the server picks), which provider, and the seconds the running extraction has taken.
 * A successful extraction closes the dialog and clears the selection.
 */
export function useMemoryExtraction(workflowId: string) {
  const extractMemory = useExtractMemory(workflowId);
  const [open, setOpen] = useState(false);
  const [selectedRunIds, setSelectedRunIds] = useState<string[]>([]);
  const [provider, setProvider] = useState('CLAUDE');
  const [elapsedSeconds, setElapsedSeconds] = useState(0);

  useEffect(() => {
    if (!extractMemory.isPending) return;
    const interval = setInterval(() => setElapsedSeconds((s) => s + 1), 1000);
    return () => clearInterval(interval);
  }, [extractMemory.isPending]);

  const setRunSelected = (runId: string, selected: boolean) => {
    setSelectedRunIds(selected ? [...selectedRunIds, runId] : selectedRunIds.filter((id) => id !== runId));
  };

  const extract = () => {
    setElapsedSeconds(0);
    extractMemory.mutate(
      { runIds: selectedRunIds.length > 0 ? selectedRunIds : undefined, provider },
      {
        onSuccess: () => {
          setOpen(false);
          setSelectedRunIds([]);
        },
      }
    );
  };

  return {
    open,
    show: () => setOpen(true),
    close: () => setOpen(false),
    selectedRunIds,
    setRunSelected,
    provider,
    setProvider,
    elapsedSeconds,
    isPending: extractMemory.isPending,
    extract,
  };
}

export type MemoryExtraction = ReturnType<typeof useMemoryExtraction>;
