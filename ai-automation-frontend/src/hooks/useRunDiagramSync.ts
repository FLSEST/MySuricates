import { useMemo, useState } from 'react';
import { latestStepByBlock } from '../components/workflow/stepMetadata';
import type { DiagramSelection, RunStatus } from '../components/workflow/bpmnLayout';
import type { StepResult } from '../types';

/** How long a step stays highlighted after a click on its box in the diagram. */
const STEP_HIGHLIGHT_MS = 1800;

/**
 * Verbindet Ablauf-Diagramm und Schrittliste der Laufseite: `statusByBlock` färbt die Kästen
 * (laufend, fertig, fehlgeschlagen), `selectStep` scrollt beim Klick auf einen Kasten zum Step des
 * Blocks und hebt ihn kurz hervor (`stepHighlight`). Ein Block, der noch nicht lief, bleibt stumm.
 */
export function useRunDiagramSync(steps: StepResult[] | undefined) {
  // Diagramm-Kästen sind nach Block-Position nummeriert, Steps nach stepIndex — der läuft nach jedem
  // Wrapper-Block (foreach, while, …) voraus, deshalb die Zuordnung über latestStepByBlock.
  const stepByBlock = useMemo(() => latestStepByBlock(steps ?? []), [steps]);
  const statusByBlock = useMemo(() => {
    const map = new Map<number, RunStatus>();
    for (const [blockIndex, step] of stepByBlock) {
      const s = step.status === 'RUNNING' ? 'running' : step.status === 'COMPLETED' ? 'completed' : step.status === 'FAILED' ? 'failed' : undefined;
      if (s) map.set(blockIndex, s);
    }
    return map;
  }, [stepByBlock]);

  // Klick im Diagramm scrollt zum Step des Blocks und hebt ihn kurz hervor
  const [stepHighlight, setStepHighlight] = useState<{ index: number; nonce: number } | null>(null);
  const selectStep = (sel: DiagramSelection) => {
    const step = stepByBlock.get(sel.blockIndex);
    if (!step) return; // Block ist (noch) nicht gelaufen
    document.getElementById(`exec-step-${step.stepIndex}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setStepHighlight((prev) => ({ index: step.stepIndex, nonce: (prev?.nonce ?? 0) + 1 }));
    window.setTimeout(() => setStepHighlight(null), STEP_HIGHLIGHT_MS);
  };

  return { statusByBlock, stepHighlight, selectStep };
}
